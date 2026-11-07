//! The L.A.R.A Hub backend. The Tauri window and the headless `lara-hub` binary both call
//! [`serve`]; nothing here depends on a GUI.

pub mod auth;
pub mod clock;
pub mod config;
pub mod db;
pub mod discovery;
pub mod error;
pub mod models;
pub mod network;
pub mod routes;
pub mod services;
pub mod storage;
pub mod websocket;

use std::net::{Ipv4Addr, SocketAddr};
use std::path::PathBuf;
use std::sync::Arc;

use sqlx::migrate::MigrateError;
use sqlx::SqlitePool;
use tokio::net::TcpListener;
use tokio::sync::watch;
use tokio::task::JoinHandle;

use auth::limiter::LoginLimiter;
use auth::pin::PinHasher;
use clock::{Clock, SystemClock};
pub use config::HubConfig;
use storage::Storage;
use websocket::broker::Broker;

#[derive(Clone)]
pub struct AppState {
    pub db: SqlitePool,
    pub broker: Arc<Broker>,
    /// argon2id with a cap on concurrent hashes (`auth::pin`).
    pub pins: Arc<PinHasher>,
    /// Five login attempts a minute per `lrn_or_id` (`auth::limiter`).
    pub login_limiter: Arc<LoginLimiter>,
    pub clock: Arc<dyn Clock>,
    /// From `hub_meta`, created on first run (`db::load_hub_meta`).
    pub hub_id: Arc<str>,
    pub sync_epoch: i64,
    /// `<data_dir>/releases`: the client installers the captive portal (`routes/portal.rs`) serves.
    /// The installer step (1.4) and the build put the APK and desktop packages here.
    pub releases_dir: Arc<std::path::Path>,
    /// `<data_dir>/storage`: lesson files and homework (`storage`, step 3.3).
    pub storage: Arc<Storage>,
}

#[derive(Debug, thiserror::Error)]
pub enum StartError {
    #[error("could not create the data folder {path}: {source}")]
    DataDir { path: PathBuf, source: std::io::Error },
    #[error("could not prepare the file storage folder: {0}")]
    Storage(std::io::Error),
    #[error("could not open the database: {0}")]
    Database(#[from] sqlx::Error),
    #[error("could not apply database migrations: {0}")]
    Migrate(#[from] MigrateError),
    #[error("could not listen on port {port}: {source}")]
    Bind { port: u16, source: std::io::Error },
    #[error("could not create the test accounts: {0}")]
    TestSeed(String),
}

/// A running Hub. Dropping it leaves the listeners running; call [`HubHandle::shutdown`].
pub struct HubHandle {
    state: AppState,
    http_addr: SocketAddr,
    ws_addr: SocketAddr,
    shutdown: watch::Sender<bool>,
    tasks: Vec<JoinHandle<()>>,
    /// Advertises `_lara._tcp.local` for its whole life; dropping it unregisters the service.
    /// `None` when mDNS could not start (the UDP beacon still runs).
    _mdns: Option<discovery::mdns::MdnsResponder>,
}

impl HubHandle {
    pub fn state(&self) -> &AppState {
        &self.state
    }

    pub fn http_addr(&self) -> SocketAddr {
        self.http_addr
    }

    pub fn ws_addr(&self) -> SocketAddr {
        self.ws_addr
    }

    pub async fn shutdown(self) {
        let _ = self.shutdown.send(true);
        for task in self.tasks {
            let _ = task.await;
        }
        self.state.db.close().await;
    }
}

/// Opens the database, applies migrations, then listens on HTTP and WebSocket.
pub async fn serve(config: HubConfig) -> Result<HubHandle, StartError> {
    tokio::fs::create_dir_all(&config.data_dir)
        .await
        .map_err(|source| StartError::DataDir { path: config.data_dir.clone(), source })?;
    let storage = Storage::open(&config.data_dir).await.map_err(StartError::Storage)?;
    let db = db::connect(&config.data_dir).await?;
    db::migrate(&db).await?;
    let meta = db::load_hub_meta(&db).await?;

    let state = AppState {
        db,
        broker: Arc::default(),
        pins: Arc::default(),
        login_limiter: Arc::default(),
        clock: Arc::new(SystemClock),
        hub_id: meta.hub_id.into(),
        sync_epoch: meta.sync_epoch,
        releases_dir: Arc::from(config.data_dir.join("releases").as_path()),
        storage: Arc::new(storage),
    };
    // Before the listeners open, so no request finds the accounts missing.
    if config.test_seed {
        db::seed::mock_accounts(&state.db, &state.pins, state.clock.now_ms()).await?;
    }

    let (http, http_addr) = bind(config.http_port).await?;
    let (ws, ws_addr) = bind(config.ws_port).await?;
    let (shutdown, stop) = watch::channel(false);

    let router = routes::router(state.clone());
    let mut http_stop = stop.clone();
    let http_task = tokio::spawn(async move {
        // Connect info lets admin setup and admin login (step 2.0) tell the Hub PC from the network.
        let served = axum::serve(http, router.into_make_service_with_connect_info::<SocketAddr>())
            .with_graceful_shutdown(async move {
                let _ = http_stop.changed().await;
            })
            .await;
        if let Err(e) = served {
            tracing::error!(error = %e, "HTTP listener stopped");
        }
    });
    let ws_task = tokio::spawn(websocket::run(ws, state.clone(), stop.clone()));

    // Discovery advertises the ports we actually bound (tests use ephemeral ports, so this must be
    // the real address, not the configured one). Both are best-effort: a router that blocks them
    // never stops the Hub, because clients can always type the IP by hand.
    let beacon_info = discovery::udp::BeaconInfo {
        name: config.hub_name.clone(),
        http_port: http_addr.port(),
        ws_port: ws_addr.port(),
    };
    let beacon_task = tokio::spawn(discovery::udp::run(beacon_info, stop));
    let mdns = discovery::mdns::start(&config.hub_name, http_addr.port(), discovery::lan_ip());

    tracing::info!(%http_addr, %ws_addr, "Hub listening");
    Ok(HubHandle {
        state,
        http_addr,
        ws_addr,
        shutdown,
        tasks: vec![http_task, ws_task, beacon_task],
        _mdns: mdns,
    })
}

async fn bind(port: u16) -> Result<(TcpListener, SocketAddr), StartError> {
    let fail = |source| StartError::Bind { port, source };
    let listener = TcpListener::bind((Ipv4Addr::UNSPECIFIED, port)).await.map_err(fail)?;
    let addr = listener.local_addr().map_err(fail)?;
    Ok((listener, addr))
}

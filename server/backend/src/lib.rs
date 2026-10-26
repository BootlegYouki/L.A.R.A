//! The L.A.R.A Hub backend. The Tauri window and the headless `lara-hub` binary both call
//! [`serve`]; nothing here depends on a GUI.

pub mod auth;
pub mod clock;
pub mod config;
pub mod db;
pub mod discovery;
pub mod error;
pub mod routes;
pub mod websocket;

use std::net::{Ipv4Addr, SocketAddr};
use std::path::PathBuf;
use std::sync::Arc;

use sqlx::migrate::MigrateError;
use sqlx::SqlitePool;
use tokio::net::TcpListener;
use tokio::sync::watch;
use tokio::task::JoinHandle;

use auth::Sessions;
use clock::{Clock, SystemClock};
pub use config::HubConfig;
use websocket::broker::Broker;

#[derive(Clone)]
pub struct AppState {
    pub db: SqlitePool,
    pub broker: Arc<Broker>,
    pub sessions: Arc<Sessions>,
    pub clock: Arc<dyn Clock>,
    /// From `hub_meta`, created on first run (`db::load_hub_meta`).
    pub hub_id: Arc<str>,
    pub sync_epoch: i64,
}

#[derive(Debug, thiserror::Error)]
pub enum StartError {
    #[error("could not create the data folder {path}: {source}")]
    DataDir { path: PathBuf, source: std::io::Error },
    #[error("could not open the database: {0}")]
    Database(#[from] sqlx::Error),
    #[error("could not apply database migrations: {0}")]
    Migrate(#[from] MigrateError),
    #[error("could not listen on port {port}: {source}")]
    Bind { port: u16, source: std::io::Error },
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
    let db = db::connect(&config.data_dir).await?;
    db::migrate(&db).await?;
    let meta = db::load_hub_meta(&db).await?;

    let state = AppState {
        db,
        broker: Arc::default(),
        sessions: Arc::default(),
        clock: Arc::new(SystemClock),
        hub_id: meta.hub_id.into(),
        sync_epoch: meta.sync_epoch,
    };

    let (http, http_addr) = bind(config.http_port).await?;
    let (ws, ws_addr) = bind(config.ws_port).await?;
    let (shutdown, stop) = watch::channel(false);

    let router = routes::router(state.clone());
    let mut http_stop = stop.clone();
    let http_task = tokio::spawn(async move {
        // Connect info lets the admin setup route (step 2.3) accept only loopback callers.
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

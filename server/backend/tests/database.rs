use lara_hub::HubConfig;

/// Clients compare `hub_id` to detect a different Hub, so a restart must not look like one.
#[tokio::test]
async fn hub_id_survives_a_restart() {
    let data_dir = tempfile::tempdir().unwrap();
    let config = || HubConfig { http_port: 0, ws_port: 0, ..HubConfig::new(data_dir.path()) };

    let hub = lara_hub::serve(config()).await.unwrap();
    let first = hub.state().hub_id.clone();
    let stored: String = sqlx::query_scalar("SELECT value FROM hub_meta WHERE key = 'hub_id'")
        .fetch_one(&hub.state().db)
        .await
        .unwrap();
    assert_eq!((&*first, hub.state().sync_epoch), (stored.as_str(), 1));
    hub.shutdown().await;

    let hub = lara_hub::serve(config()).await.unwrap();
    assert_eq!(hub.state().hub_id, first);
    hub.shutdown().await;
}

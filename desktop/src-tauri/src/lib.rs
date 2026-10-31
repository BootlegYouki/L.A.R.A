mod discovery;
use tauri_plugin_sql::{Migration, MigrationKind};

pub fn run() {
    tauri::Builder::default()
        .manage(discovery::Discovery::default())
        .setup(|app| {
            discovery::start(app.handle().clone());
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![discovery::get_discovered_hubs])
        .plugin(
            tauri_plugin_sql::Builder::default()
                .add_migrations(
                    "sqlite:lara_client.db",
                    vec![Migration {
                        version: 1,
                        description: "client_offline_schema",
                        sql: include_str!("../migrations/001_client_offline.sql"),
                        kind: MigrationKind::Up,
                    }],
                )
                .build(),
        )
        .build(tauri::generate_context!())
        .expect("could not build the L.A.R.A desktop window")
        .run(|app, event| {
            if matches!(event, tauri::RunEvent::Exit) {
                discovery::stop(app);
            }
        });
}

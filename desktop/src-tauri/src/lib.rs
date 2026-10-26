use tauri_plugin_sql::{Migration, MigrationKind};

pub fn run() {
    tauri::Builder::default()
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
        .run(tauri::generate_context!())
        .expect("could not run the L.A.R.A desktop window");
}

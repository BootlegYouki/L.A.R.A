mod discovery;
use tauri_plugin_sql::{Migration, MigrationKind};

pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_http::init())
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
                    vec![
                        Migration {
                            version: 1,
                            description: "client_offline_schema",
                            sql: include_str!("../migrations/001_client_offline.sql"),
                            kind: MigrationKind::Up,
                        },
                        Migration {
                            version: 2,
                            description: "sync_rejections",
                            sql: include_str!("../migrations/002_sync_rejections.sql"),
                            kind: MigrationKind::Up,
                        },
                    ],
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
#[cfg(test)]
mod permissions {
    #[test]
    fn probe_scope_accepts_only_private_ipv4_and_the_fixed_contract_path() {
        let capabilities: serde_json::Value =
            serde_json::from_str(include_str!("../capabilities/default.json")).unwrap();
        let permission = capabilities["permissions"]
            .as_array()
            .unwrap()
            .iter()
            .find(|p| p["identifier"] == "http:default")
            .unwrap();
        let patterns: Vec<urlpattern::UrlPattern> =
            permission["allow"]
                .as_array()
                .unwrap()
                .iter()
                .map(|p| {
                    let mut init = urlpattern::UrlPatternInit::parse_constructor_string::<
                        regex::Regex,
                    >(p["url"].as_str().unwrap(), None)
                    .unwrap();
                    init.search = Some("*".into());
                    init.hash = Some("*".into());
                    urlpattern::UrlPattern::parse(init, Default::default()).unwrap()
                })
                .collect();
        for ip in [
            "10.0.1.2",
            "192.168.100.2",
            "127.0.0.1",
            "172.16.0.1",
            "172.31.255.254",
        ] {
            for port in [80, 8080, 65535] {
                let url: tauri_plugin_http::reqwest::Url =
                    format!("http://{ip}:{port}/api/classrooms")
                        .parse()
                        .unwrap();
                assert!(
                    patterns.iter().any(|p| p
                        .test(urlpattern::UrlPatternMatchInput::Url(url.clone()))
                        .unwrap()),
                    "{url}"
                );
            }
        }
        for url in [
            "http://8.8.8.8:8080/api/classrooms",
            "http://172.15.0.1:8080/api/classrooms",
            "http://172.32.0.1:8080/api/classrooms",
            "http://192.168.1.2.evil.test/api/classrooms",
            "http://192.168.1.2:8080/download",
            "https://192.168.1.2:8080/api/classrooms",
        ] {
            assert!(
                !patterns.iter().any(|p| p
                    .test(urlpattern::UrlPatternMatchInput::Url(url.parse().unwrap()))
                    .unwrap()),
                "{url}"
            );
        }
    }
}

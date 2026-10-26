// sqlx::migrate! embeds the migration files at compile time; without this a new
// file in src/db/migrations would not trigger a rebuild and would never run.
fn main() {
    println!("cargo:rerun-if-changed=src/db/migrations");
}

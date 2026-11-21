use std::net::SocketAddr;

use axum::extract::{rejection::QueryRejection, ConnectInfo, Query, State};
use axum::http::{HeaderMap, StatusCode};
use axum::routing::get;
use axum::{Json, Router};
use serde::{Deserialize, Serialize};
use sqlx::{QueryBuilder, Row, Sqlite};

use crate::auth::{is_hub_pc, require_role, AuthUser, Role};
use crate::error::{ApiError, ErrorCode};
use crate::services::activity::{Details, Entry};
use crate::AppState;

pub fn router() -> Router<AppState> {
    Router::new().route("/api/admin/activity-logs", get(read))
}

#[derive(Default, Deserialize)]
#[serde(deny_unknown_fields)]
struct Filters {
    limit: Option<i64>,
    before_seq: Option<i64>,
    after_seq: Option<i64>,
    snapshot_seq: Option<i64>,
    history_id: Option<String>,
    from_time: Option<i64>,
    to_time: Option<i64>,
    severity: Option<String>,
    q: Option<String>,
}
impl Filters {
    fn validate(&self) -> Result<(), ApiError> {
        let bad = || ApiError::bad_request("Invalid activity filters");
        if !(1..=100).contains(&self.limit.unwrap_or(100)) || (self.before_seq.is_some() && self.after_seq.is_some()) {
            return Err(bad());
        }
        for value in
            [self.before_seq, self.after_seq, self.snapshot_seq, self.from_time, self.to_time].into_iter().flatten()
        {
            if !(0..=9_007_199_254_740_991).contains(&value) {
                return Err(bad());
            }
        }
        if self.before_seq == Some(0) || (self.from_time.zip(self.to_time).is_some_and(|(from, to)| from >= to)) {
            return Err(bad());
        }
        if (self.before_seq.is_some() || self.after_seq.is_some() || self.snapshot_seq.is_some())
            && self.history_id.is_none()
        {
            return Err(bad());
        }
        if self.history_id.as_ref().is_some_and(|id| uuid::Uuid::parse_str(id).is_err()) {
            return Err(bad());
        }
        if self.severity.as_ref().is_some_and(|value| !["INFO", "WARNING", "ERROR"].contains(&value.as_str()))
            || self.q.as_ref().is_some_and(|value| value.chars().count() > 64)
        {
            return Err(bad());
        }
        Ok(())
    }
}

#[derive(Serialize)]
struct Page {
    history_id: String,
    entries: Vec<Entry>,
    high_water_seq: i64,
    next_before_seq: Option<i64>,
    next_after_seq: Option<i64>,
    pruned_through_seq: i64,
    dropped_total: i64,
    recording_state: &'static str,
    server_time: i64,
}
fn unavailable(_: sqlx::Error) -> ApiError {
    ApiError::new(StatusCode::SERVICE_UNAVAILABLE, ErrorCode::ActivityLogUnavailable, "Activity history is unavailable")
}

async fn read(
    State(app): State<AppState>,
    user: AuthUser,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    filters: Result<Query<Filters>, QueryRejection>,
) -> Result<(HeaderMap, Json<Page>), ApiError> {
    require_role(&user, Role::Admin)?;
    if !is_hub_pc(peer, &headers) {
        return Err(ApiError::admin_local_only());
    }
    let Query(filters) = filters.map_err(|_| ApiError::bad_request("Invalid activity filters"))?;
    filters.validate()?;
    let mut tx = app.db.begin().await.map_err(unavailable)?;
    let (history_id, pruned, dropped): (String, i64, i64) =
        sqlx::query_as("SELECT history_id,pruned_through_seq,dropped_total FROM hub_activity_state WHERE singleton=1")
            .fetch_one(&mut *tx)
            .await
            .map_err(unavailable)?;
    if filters.history_id.as_ref().is_some_and(|id| !id.eq_ignore_ascii_case(&history_id)) {
        return Err(ApiError::new(
            StatusCode::CONFLICT,
            ErrorCode::ActivityLogReset,
            "Activity history changed; reload it",
        ));
    }
    let issued: i64 =
        sqlx::query_scalar("SELECT COALESCE((SELECT seq FROM sqlite_sequence WHERE name='hub_activity_events'),0)")
            .fetch_one(&mut *tx)
            .await
            .map_err(unavailable)?;
    let high_water = filters.snapshot_seq.unwrap_or(issued);
    if high_water > issued {
        return Err(ApiError::bad_request("Activity snapshot is ahead of history"));
    }
    let now = app.clock.now_ms();
    let mut query = QueryBuilder::<Sqlite>::new("SELECT seq,occurred_at,event_code,severity,result,service,reason,item_count,duration_ms FROM hub_activity_events WHERE seq <= ");
    query.push_bind(high_water).push(" AND occurred_at >= ").push_bind(now.saturating_sub(7 * 86_400_000));
    for (column, operator, value) in [
        ("seq", " < ", filters.before_seq),
        ("seq", " > ", filters.after_seq),
        ("occurred_at", " >= ", filters.from_time),
        ("occurred_at", " < ", filters.to_time),
    ] {
        if let Some(value) = value {
            query.push(" AND ").push(column).push(operator).push_bind(value);
        }
    }
    if let Some(severity) = &filters.severity {
        query.push(" AND severity = ").push_bind(severity);
    }
    if let Some(search) =
        filters.q.as_ref().map(|q| q.trim().replace('_', " ").to_lowercase()).filter(|q| !q.is_empty())
    {
        query.push(" AND instr(lower(replace(event_code || ' ' || service || ' ' || result || ' ' || COALESCE(reason,''),'_',' ')),").push_bind(search).push(") > 0");
    }
    let newer = filters.after_seq.is_some();
    let limit = filters.limit.unwrap_or(100) as usize;
    query
        .push(if newer { " ORDER BY seq ASC LIMIT " } else { " ORDER BY seq DESC LIMIT " })
        .push_bind((limit + 1) as i64);
    let rows = query.build().fetch_all(&mut *tx).await.map_err(unavailable)?;
    let more = rows.len() > limit;
    let mut entries = Vec::with_capacity(limit);
    for row in rows.into_iter().take(limit) {
        entries.push(Entry {
            seq: row.try_get("seq").map_err(unavailable)?,
            occurred_at: row.try_get("occurred_at").map_err(unavailable)?,
            event_code: row.try_get("event_code").map_err(unavailable)?,
            severity: row.try_get("severity").map_err(unavailable)?,
            result: row.try_get("result").map_err(unavailable)?,
            service: row.try_get("service").map_err(unavailable)?,
            details: Details {
                reason: row.try_get("reason").map_err(unavailable)?,
                item_count: row.try_get("item_count").map_err(unavailable)?,
                duration_ms: row.try_get("duration_ms").map_err(unavailable)?,
            },
        });
    }
    entries.sort_by_key(|entry| std::cmp::Reverse(entry.seq));
    let next_before_seq = if more && !newer { entries.last().map(|entry| entry.seq) } else { None };
    let next_after_seq = if more && newer { entries.first().map(|entry| entry.seq) } else { None };
    tx.commit().await.map_err(unavailable)?;
    let mut response_headers = HeaderMap::new();
    response_headers.insert(axum::http::header::CACHE_CONTROL, axum::http::HeaderValue::from_static("no-store"));
    Ok((
        response_headers,
        Json(Page {
            history_id,
            entries,
            high_water_seq: high_water,
            next_before_seq,
            next_after_seq,
            pruned_through_seq: pruned,
            dropped_total: dropped.saturating_add(app.activity.pending_drops()).min(9_007_199_254_740_991),
            recording_state: app.activity.state(),
            server_time: now,
        }),
    ))
}

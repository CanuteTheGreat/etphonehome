//! Reusable UI components

// log_viewer, profile_list, status_bar are standalone components not yet
// wired into any view (views/logs.rs, views/profiles.rs, and app.rs's
// status area currently render their own inline markup instead). Kept as
// reference implementations for when those views get refactored to use
// them, rather than deleted.
#[allow(dead_code)]
pub mod log_viewer;
#[allow(dead_code)]
pub mod profile_list;
pub mod stats_card;
#[allow(dead_code)]
pub mod status_bar;

# Architect's Memory: Trade-offs & Overrides
This log tracks intentional deviations from standard ADRs to prevent the Audit Agent from re-flagging valid exceptions.

| Date | File/Module | Pattern Flagged | Override Reason (The "Why") | Approved By |
| :--- | :--- | :--- | :--- | :--- |
| 2026-03-26 | `Moxter/DiskExecutor.java` | Synchronous IO | Required for legacy filesystem driver that lacks async support. | @User |
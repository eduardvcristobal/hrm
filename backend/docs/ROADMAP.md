# HRM SaaS Development Roadmap

## Main business modules
1. Personnel Management
2. Timekeeping Management
3. Payroll Management

## Foundation
- [x] M0.1 Source scaffold, Java 17 Spring Boot, PostgreSQL Compose, Flyway V1, health security, local startup
- [ ] M0.2 Authentication, authorization, request tenant isolation, RLS, migration/runtime DB role separation
- [ ] M0.3 Tenant/company/branch management, auditing, CI, integration tests

## Personnel
- [ ] M1.1 Organization, departments, positions, cost centers
- [ ] M1.2 Employee masterfile and identities
- [ ] M1.3 Employment and salary history
- [ ] M1.4 Employee documents and ESS baseline

## Timekeeping
- [ ] M2.1 Schedules, shifts, holidays
- [ ] M2.2 Mobile/manual punch ingestion
- [ ] M2.3 DTR compute and correction workflows
- [ ] M2.4 Leave and OT and approval
- [ ] M2.5 Cutoff and immutable timekeeping snapshot

## Payroll
- [ ] M3.1 Versioned rule definitions and gross-to-net engine
- [ ] M3.2 Philippine statutory computations and regression suite
- [ ] M3.3 Payroll run workflow, adjustments, payslips
- [ ] M3.4 13th month, final pay, annualization
- [ ] M3.5 Bank exports and reconciliation
- [ ] M3.6 GL, journal entries, remittances, reports
- [ ] M3.7 Parallel-run acceptance, security and production certification

Commit each completed, tested submilestone separately. Do not merge unverified work.

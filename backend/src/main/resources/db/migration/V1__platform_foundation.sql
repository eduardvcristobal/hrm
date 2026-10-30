-- M0.1: initial shared platform schema. Future migrations must be additive and reviewed.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE tenant (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    slug varchar(80) NOT NULL UNIQUE,
    display_name varchar(200) NOT NULL,
    status varchar(25) NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_tenant_status CHECK (status IN ('ACTIVE','SUSPENDED','CLOSED'))
);

CREATE TABLE company (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    company_code varchar(40) NOT NULL,
    legal_name varchar(200) NOT NULL,
    country_code char(2) NOT NULL DEFAULT 'PH',
    timezone varchar(64) NOT NULL DEFAULT 'Asia/Manila',
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_company_tenant_code UNIQUE (tenant_id, company_code),
    CONSTRAINT uq_company_tenant_id_id UNIQUE (tenant_id, id)
);
CREATE INDEX ix_company_tenant ON company(tenant_id);

CREATE TABLE branch (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    company_id uuid NOT NULL,
    branch_code varchar(40) NOT NULL,
    branch_name varchar(200) NOT NULL,
    region_code varchar(40),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_branch_company_tenant FOREIGN KEY (tenant_id,company_id)
        REFERENCES company(tenant_id,id),
    CONSTRAINT uq_branch_company_code UNIQUE (company_id,branch_code)
);
CREATE INDEX ix_branch_tenant_company ON branch(tenant_id,company_id);

CREATE TABLE audit_event (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id uuid,
    actor_id uuid,
    action varchar(100) NOT NULL,
    entity_type varchar(100) NOT NULL,
    entity_id uuid,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX ix_audit_tenant_when ON audit_event(tenant_id,occurred_at DESC);

-- M0.2 MUST introduce authenticated tenant context, RLS policies and a least-privileged DB role
-- before ANY business endpoint or tenant data access is enabled.

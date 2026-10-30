CREATE TABLE personnel_private_data (
 tenant_id uuid NOT NULL,
 employee_id uuid NOT NULL,
 government_id_ciphertext text,
 contact_email_ciphertext text,
 mobile_ciphertext text,
 bank_account_ciphertext text,
 updated_at timestamptz NOT NULL DEFAULT now(),
 updated_by uuid NOT NULL,
 PRIMARY KEY(tenant_id,employee_id),
 CONSTRAINT fk_personnel_private_employee FOREIGN KEY(tenant_id,employee_id)
   REFERENCES personnel_employee(tenant_id,id)
);
ALTER TABLE personnel_private_data ENABLE ROW LEVEL SECURITY;
ALTER TABLE personnel_private_data FORCE ROW LEVEL SECURITY;
CREATE POLICY personnel_private_tenant ON personnel_private_data
 USING(tenant_id=app_tenant_id()) WITH CHECK(tenant_id=app_tenant_id());

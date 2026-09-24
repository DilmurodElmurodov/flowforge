-- Global permission catalogue. Codes are referenced verbatim by @PreAuthorize expressions in the code base.
INSERT INTO permissions (id, code, description, version) VALUES
    ('0f2a7d1e-0001-4a5b-8c00-000000000001', 'TENANT_MANAGE',     'Provision and administer tenants', 0),
    ('0f2a7d1e-0001-4a5b-8c00-000000000002', 'USER_MANAGE',       'Create and manage users of the tenant', 0),
    ('0f2a7d1e-0001-4a5b-8c00-000000000003', 'ROLE_MANAGE',       'Create and manage roles of the tenant', 0),
    ('0f2a7d1e-0001-4a5b-8c00-000000000004', 'WORKFLOW_CREATE',   'Author workflows and versions', 0),
    ('0f2a7d1e-0001-4a5b-8c00-000000000005', 'WORKFLOW_READ',     'Read workflows and versions', 0),
    ('0f2a7d1e-0001-4a5b-8c00-000000000006', 'WORKFLOW_PUBLISH',  'Publish workflow versions', 0),
    ('0f2a7d1e-0001-4a5b-8c00-000000000007', 'WORKFLOW_EXECUTE',  'Start workflow executions', 0),
    ('0f2a7d1e-0001-4a5b-8c00-000000000008', 'EXECUTION_READ',    'Inspect executions and their step history', 0),
    ('0f2a7d1e-0001-4a5b-8c00-000000000009', 'EXECUTION_APPROVE', 'Decide on approval steps', 0),
    ('0f2a7d1e-0001-4a5b-8c00-000000000010', 'AUDIT_READ',        'Read audit information', 0);

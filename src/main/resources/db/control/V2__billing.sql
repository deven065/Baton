-- Plans. A workspace is on TEAM while paid_until is in the future, otherwise on FREE.
ALTER TABLE control.tenant ADD COLUMN paid_until TIMESTAMPTZ;

-- Baton's own payment records (kept even after a workspace disconnects). No customer content.
CREATE TABLE control.payment (
    provider_ref VARCHAR(80) PRIMARY KEY,
    tenant_id    UUID         NOT NULL,
    tenant_slug  VARCHAR(40)  NOT NULL,
    amount_minor BIGINT       NOT NULL,
    currency     CHAR(3)      NOT NULL,
    months       INT          NOT NULL DEFAULT 1,
    paid_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

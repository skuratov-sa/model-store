CREATE TYPE transfer_status AS enum (
    'ACTIVE',
    'DELETED'
    );
CREATE CAST (character varying AS transfer_status) WITH INOUT AS ASSIGNMENT;

CREATE TYPE address_status AS enum (
    'ACTIVE',
    'DELETED'
    );
CREATE CAST (character varying AS address_status) WITH INOUT AS ASSIGNMENT;

ALTER TABLE address ADD COLUMN status address_status NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE transfer ADD COLUMN status transfer_status NOT NULL DEFAULT 'ACTIVE';



DROP INDEX one_enabled_giveaway;

CREATE UNIQUE INDEX one_active_giveaway ON product ((1))
    WHERE availability = 'GIVEAWAY' AND giveaway_enabled AND status = 'ACTIVE';

CREATE INDEX product_giveaway_start_idx ON product (giveaway_start_at)
    WHERE availability = 'GIVEAWAY' AND giveaway_enabled AND status = 'AWAITING_GIVEAWAY';

CREATE INDEX product_giveaway_end_idx ON product (giveaway_end_at)
    WHERE availability = 'GIVEAWAY' AND status IN ('ACTIVE', 'AWAITING_GIVEAWAY');

CREATE INDEX product_ordinary_expiration_idx ON product (expiration_date)
    WHERE availability <> 'GIVEAWAY' AND status = 'ACTIVE';

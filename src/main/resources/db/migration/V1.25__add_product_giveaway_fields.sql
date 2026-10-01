ALTER TABLE product
    ADD COLUMN giveaway_enabled boolean NOT NULL DEFAULT false,
    ADD COLUMN giveaway_telegram_url varchar(1000),
    ADD COLUMN giveaway_start_at timestamptz,
    ADD COLUMN giveaway_end_at timestamptz,
    ADD COLUMN giveaway_winners_count integer,
    ADD COLUMN giveaway_rules text,
    ADD COLUMN giveaway_home_text text;

ALTER TABLE product
    ADD CONSTRAINT giveaway_valid_dates CHECK (
        giveaway_start_at IS NULL OR giveaway_end_at IS NULL OR giveaway_start_at < giveaway_end_at
    ),
    ADD CONSTRAINT giveaway_valid_winners CHECK (
        giveaway_winners_count IS NULL OR giveaway_winners_count > 0
    ),
    ADD CONSTRAINT giveaway_enabled_has_settings CHECK (
        NOT giveaway_enabled OR (
            availability = 'GIVEAWAY'
            AND giveaway_telegram_url IS NOT NULL
            AND giveaway_start_at IS NOT NULL
            AND giveaway_end_at IS NOT NULL
            AND giveaway_winners_count IS NOT NULL
            AND giveaway_rules IS NOT NULL
            AND giveaway_home_text IS NOT NULL
        )
    );

CREATE UNIQUE INDEX one_enabled_giveaway ON product (giveaway_enabled)
    WHERE availability = 'GIVEAWAY' AND giveaway_enabled;

CREATE INDEX product_giveaway_history_idx ON product (giveaway_end_at DESC, id DESC)
    WHERE giveaway_end_at IS NOT NULL;

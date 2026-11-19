package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V1_32__order_photo_and_registration_identity extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        try (var statement = context.getConnection().createStatement()) {
            statement.execute("""
                ALTER TABLE "order" ADD COLUMN product_image_id bigint REFERENCES image(id);
                UPDATE "order" o SET product_image_id = (
                    SELECT i.id FROM image i
                    WHERE i.tag = 'PRODUCT' AND i.entity_id = o.product_id AND i.status = 'ACTIVE'
                    ORDER BY i.created_at, i.id LIMIT 1
                );
                CREATE INDEX order_product_image_idx ON "order"(product_image_id)
                    WHERE product_image_id IS NOT NULL;
                ALTER TABLE participant ADD COLUMN legacy_mail_collision boolean NOT NULL DEFAULT false;
                UPDATE participant p SET legacy_mail_collision = true
                FROM (
                    SELECT lower(btrim(mail)) AS normalized_mail FROM participant
                    WHERE mail IS NOT NULL GROUP BY lower(btrim(mail)) HAVING count(*) > 1
                ) AS duplicates
                WHERE lower(btrim(p.mail)) = duplicates.normalized_mail;
                CREATE INDEX participant_normalized_mail_lookup_idx ON participant(lower(btrim(mail)));
                CREATE UNIQUE INDEX uq_participant_normalized_mail ON participant(lower(btrim(mail)))
                    WHERE NOT legacy_mail_collision;

                CREATE FUNCTION reject_reserved_legacy_mail() RETURNS trigger AS $$
                BEGIN
                    IF TG_OP = 'UPDATE' THEN
                        IF NEW.mail IS NOT DISTINCT FROM OLD.mail THEN
                            RETURN NEW;
                        END IF;
                    END IF;
                    IF EXISTS (
                        SELECT 1 FROM participant p WHERE p.legacy_mail_collision
                          AND p.id IS DISTINCT FROM NEW.id
                          AND lower(btrim(p.mail)) = lower(btrim(NEW.mail))
                    ) THEN
                        RAISE EXCEPTION USING ERRCODE = '23505', CONSTRAINT = 'uq_participant_normalized_mail',
                                  MESSAGE = 'duplicate key value violates unique constraint "uq_participant_normalized_mail"';
                    END IF;
                    NEW.legacy_mail_collision := false;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql;
                CREATE TRIGGER reserve_legacy_mail_before_write
                    BEFORE INSERT OR UPDATE OF mail ON participant
                    FOR EACH ROW EXECUTE FUNCTION reject_reserved_legacy_mail();
                """);
        }
    }
}

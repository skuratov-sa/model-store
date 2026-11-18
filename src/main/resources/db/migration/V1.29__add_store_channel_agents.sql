-- Channel identities follow the existing agentFigurkin technical-mail convention.
-- Existing accounts, their IDs, credentials, status and related data are preserved.
-- This migration creates accounts only; agent JWTs are issued by the admin API.
DO $$
DECLARE
    channel RECORD;
    matching_ids BIGINT[];
    existing participant%ROWTYPE;
BEGIN
    -- Serialize identity checks with concurrent participant writes, including registration.
    LOCK TABLE participant IN SHARE ROW EXCLUSIVE MODE;

    FOR channel IN
        SELECT * FROM (VALUES
            ('animestyle',    'agentAnimestyle',   'animestyle',      false),
            ('bigshelf',      'agentBigShelf',     'BIG SHELF',       false),
            ('figurkin',      'agentFigurkin',     'Агент Фигуркин',  true),
            ('anime_figures', 'agentAnimeFigures', 'Фигурки Аниме',   false),
            ('kitsune_castle','agentKitsuneCastle','Замок Кицунэ',    false),
            ('miomio',        'agentMioMio',       'MioMio',          false)
        ) AS desired(login, mail, full_name, existing_only)
    LOOP
        SELECT array_agg(p.id ORDER BY p.id)
        INTO matching_ids
        FROM participant p
        WHERE lower(p.login) = lower(channel.login)
           OR lower(p.mail) = lower(channel.mail)
           OR (p.is_agent AND lower(btrim(p.full_name)) = lower(channel.full_name));

        IF coalesce(cardinality(matching_ids), 0) > 1 THEN
            RAISE EXCEPTION 'Ambiguous channel agent %; matching participant IDs: %',
                channel.login, matching_ids USING ERRCODE = '23505';
        END IF;

        IF coalesce(cardinality(matching_ids), 0) = 1 THEN
            SELECT * INTO STRICT existing FROM participant WHERE id = matching_ids[1];
            IF NOT existing.is_agent THEN
                RAISE EXCEPTION 'Channel agent % conflicts with a non-agent participant (id=%)',
                    channel.login, existing.id USING ERRCODE = '23505';
            END IF;
            IF existing.mail IS NULL OR btrim(existing.mail) = ''
                OR existing.login IS NULL OR btrim(existing.login) = '' THEN
                RAISE EXCEPTION 'Existing channel agent % (id=%) has no usable login or technical mail',
                    channel.login, existing.id;
            END IF;
            -- In particular, do not reactivate a blocked agent or rotate existing credentials.
            CONTINUE;
        END IF;

        IF channel.existing_only THEN
            RAISE EXCEPTION 'Existing Figurkin account was not found; refusing to create a replacement';
        END IF;

        INSERT INTO participant
            (login, mail, full_name, password, role, status, deadline_sending,
             deadline_payment, seller_status, is_agent, created_at)
        VALUES
            (channel.login, channel.mail, channel.full_name, '!agent-token-only!', 'USER', 'ACTIVE',
             1, 1, 'DEFAULT', true, CURRENT_TIMESTAMP);
        -- The password marker is deliberately not a BCrypt hash: no shared password is installed.
    END LOOP;
END;
$$;

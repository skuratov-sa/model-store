WITH
    p1 AS (
        INSERT INTO participant (login, password, role, mail, full_name, phone_number, status, deadline_sending, deadline_payment, created_at)
            VALUES ('figurkin', '$2a$10$cBH4aAAO9oORDBkMDRTgLOpgTkKuJvJPVvcwUiYSHbmnooKUIh1XS', 'USER', 'agentFigurkin', 'Агент Фигуркин', '+79991234567', 'ACTIVE', 1, 1, current_timestamp)
            RETURNING id
    ),
    p2 AS (
        INSERT INTO participant (login, password, role, mail, full_name, phone_number, status, deadline_sending, deadline_payment, created_at)
            VALUES ('figovBaron', '$2a$10$cBH4aAAO9oORDBkMDRTgLOpgTkKuJvJPVvcwUiYSHbmnooKUIh1XS', 'USER', 'agentFigovBaron', 'Агент Фигов Барон', '+79991234568', 'ACTIVE', 1, 1, current_timestamp)
            RETURNING id
    ),

    a1 AS (
        INSERT INTO address (country, city, street, house_number, apartment_number, "index", status)
            VALUES ('RUB', 'Moscow', 'Tverskaya', '1', '10', '101000', 'ACTIVE')
            RETURNING id
    ),
    a2 AS (
        INSERT INTO address (country, city, street, house_number, apartment_number, "index", status)
            VALUES ('RUB', 'Moscow', 'Arbat', '12', '5', '119002', 'ACTIVE')
            RETURNING id
    ),

    link1 AS (
        INSERT INTO participant_address (participant_id, address_id)
            SELECT p1.id, a1.id FROM p1, a1
            RETURNING 1
    ),
    link2 AS (
        INSERT INTO participant_address (participant_id, address_id)
            SELECT p2.id, a2.id FROM p2, a2
            RETURNING 1
    ),

    sn1 AS (
        INSERT INTO social_network (participant_id, type, login)
            SELECT p1.id, 'TELEGRAM', 'figurkin_tg' FROM p1
            RETURNING 1
    ),
    sn2 AS (
        INSERT INTO social_network (participant_id, type, login)
            SELECT p2.id, 'VK', 'figov_baron' FROM p2
            RETURNING 1
    ),

    tr1 AS (
        INSERT INTO transfer (sending, price, currency, participant_id, status)
            SELECT 'RUSSIAN_POST', 0, 'RUB', p1.id, 'ACTIVE' FROM p1
            RETURNING 1
    ),
    tr2 AS (
        INSERT INTO transfer (sending, price, currency, participant_id, status)
            SELECT 'RUSSIAN_POST', 0, 'RUB', p2.id, 'ACTIVE' FROM p2
            RETURNING 1
    )
SELECT;
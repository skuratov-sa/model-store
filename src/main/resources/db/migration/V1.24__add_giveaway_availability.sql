ALTER TYPE product_availability ADD VALUE IF NOT EXISTS 'GIVEAWAY';

INSERT INTO dictionary (type, value, description)
VALUES ('PRODUCT_AVAILABILITY', 'GIVEAWAY', 'Розыгрыш');

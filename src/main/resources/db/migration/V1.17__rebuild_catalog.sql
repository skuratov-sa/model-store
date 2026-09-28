ALTER TABLE product ADD COLUMN used BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE category ADD COLUMN display_order INTEGER;

-- The slug identifies an existing row; renames and moves retain its numeric ID.
CREATE TEMP TABLE desired_category (
    slug VARCHAR(100) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    parent_slug VARCHAR(100),
    display_order INTEGER NOT NULL,
    depth INTEGER NOT NULL
) ON COMMIT DROP;

INSERT INTO desired_category (slug, name, parent_slug, display_order, depth) VALUES
    ('catalog', 'Каталог', NULL, 1, 0),
    ('anime_figures', 'Фигурки', 'catalog', 1, 1),
    ('anime_cards', 'Карточки', 'catalog', 2, 1),
    ('manga_books', 'Манга', 'catalog', 3, 1),
    ('irrelevant', 'Другое', 'catalog', 4, 1),
    ('figures_by_type', 'Тип', 'anime_figures', 1, 2),
    ('figures_by_manufacturer', 'Производитель', 'anime_figures', 2, 2),
    ('figures_by_franchise', 'Title', 'anime_figures', 3, 2),
    ('cards_by_game', 'TCG', 'anime_cards', 1, 2),
    ('cards_by_type', 'Тип', 'anime_cards', 2, 2),
    ('manga_by_type', 'Тип', 'manga_books', 1, 2),
    ('manga_by_genre', 'Жанр', 'manga_books', 2, 2),
    ('manga_by_publisher', 'Издатель', 'manga_books', 3, 2),
    ('scale_figure', 'Scale Figure', 'figures_by_type', 1, 3),
    ('statue_bust', 'Statue / Bust', 'figures_by_type', 2, 3),
    ('nendoroid', 'Nendoroid', 'figures_by_type', 3, 3),
    ('figma', 'Figma', 'figures_by_type', 4, 3),
    ('chibi', 'Chibi / Deformed', 'figures_by_type', 5, 3),
    ('garage_kit', 'Garage Kit (GK)', 'figures_by_type', 6, 3),
    ('gashapon', 'Gashapon / Capsule', 'figures_by_type', 7, 3),
    ('plush', 'Plush / Мягкие игрушки', 'figures_by_type', 8, 3),
    ('acrylic_stand', 'Акрил / Стенды', 'figures_by_type', 9, 3),
    ('nsfw_adult', 'NSFW (18+)', 'figures_by_type', 10, 3),
    ('other_figure_type', 'Другое', 'figures_by_type', 11, 3),
    ('gsc', 'Good Smile Company', 'figures_by_manufacturer', 1, 3),
    ('max_factory', 'Max Factory', 'figures_by_manufacturer', 2, 3),
    ('alter', 'Alter', 'figures_by_manufacturer', 3, 3),
    ('kotobukiya', 'Kotobukiya', 'figures_by_manufacturer', 4, 3),
    ('bandai_banpresto', 'Bandai / Banpresto', 'figures_by_manufacturer', 5, 3),
    ('aniplex', 'Aniplex', 'figures_by_manufacturer', 6, 3),
    ('sega_sfire', 'Sega / S-Fire', 'figures_by_manufacturer', 7, 3),
    ('furyu', 'Furyu', 'figures_by_manufacturer', 8, 3),
    ('taito', 'Taito', 'figures_by_manufacturer', 9, 3),
    ('freeing', 'Freeing', 'figures_by_manufacturer', 10, 3),
    ('union_creative', 'Union Creative', 'figures_by_manufacturer', 11, 3),
    ('myethos', 'Myethos', 'figures_by_manufacturer', 12, 3),
    ('apex_anigame', 'Apex / AniGame', 'figures_by_manufacturer', 13, 3),
    ('megahouse', 'MegaHouse', 'figures_by_manufacturer', 14, 3),
    ('limbus_company', 'Limbus Company', 'figures_by_manufacturer', 15, 3),
    ('laoa', 'LaoA', 'figures_by_manufacturer', 16, 3),
    ('other_manufacturer', 'Другое', 'figures_by_manufacturer', 17, 3),
    ('vocaloid_miku', 'Vocaloid / Hatsune Miku', 'figures_by_franchise', 1, 3),
    ('genshin_impact', 'Genshin Impact', 'figures_by_franchise', 2, 3),
    ('honkai_star_rail', 'Honkai: Star Rail', 'figures_by_franchise', 3, 3),
    ('zenless_zone_zero', 'Zenless Zone Zero', 'figures_by_franchise', 4, 3),
    ('azur_lane', 'Azur Lane', 'figures_by_franchise', 5, 3),
    ('arknights', 'Arknights', 'figures_by_franchise', 6, 3),
    ('blue_archive', 'Blue Archive', 'figures_by_franchise', 7, 3),
    ('fate', 'Fate / Stay Night', 'figures_by_franchise', 8, 3),
    ('evangelion', 'Evangelion', 'figures_by_franchise', 9, 3),
    ('naruto', 'Naruto / Boruto', 'figures_by_franchise', 10, 3),
    ('one_piece', 'One Piece', 'figures_by_franchise', 11, 3),
    ('bleach', 'Bleach', 'figures_by_franchise', 12, 3),
    ('dragon_ball', 'Dragon Ball', 'figures_by_franchise', 13, 3),
    ('demon_slayer', 'Demon Slayer', 'figures_by_franchise', 14, 3),
    ('jujutsu_kaisen', 'Jujutsu Kaisen', 'figures_by_franchise', 15, 3),
    ('chainsaw_man', 'Chainsaw Man', 'figures_by_franchise', 16, 3),
    ('attack_on_titan', 'Attack on Titan', 'figures_by_franchise', 17, 3),
    ('my_hero_academia', 'My Hero Academia', 'figures_by_franchise', 18, 3),
    ('one_punch_man', 'One Punch Man', 'figures_by_franchise', 19, 3),
    ('fma', 'Fullmetal Alchemist', 'figures_by_franchise', 20, 3),
    ('hxh', 'Hunter x Hunter', 'figures_by_franchise', 21, 3),
    ('sailor_moon', 'Sailor Moon', 'figures_by_franchise', 22, 3),
    ('rezero', 'Re:Zero', 'figures_by_franchise', 23, 3),
    ('sword_art_online', 'Sword Art Online', 'figures_by_franchise', 24, 3),
    ('overlord', 'Overlord', 'figures_by_franchise', 25, 3),
    ('spy_x_family', 'Spy x Family', 'figures_by_franchise', 26, 3),
    ('tokyo_revengers', 'Tokyo Revengers', 'figures_by_franchise', 27, 3),
    ('lycoris_recoil', 'Lycoris Recoil', 'figures_by_franchise', 28, 3),
    ('danganronpa', 'Danganronpa', 'figures_by_franchise', 29, 3),
    ('warhammer', 'Warhammer', 'figures_by_franchise', 30, 3),
    ('gunpla_gundam', 'Gunpla (Gundam)', 'figures_by_franchise', 31, 3),
    ('date_a_live', 'Date A Live!', 'figures_by_franchise', 32, 3),
    ('mira', 'MIRA', 'figures_by_franchise', 33, 3),
    ('fandeltales_cursed_prince', 'FandelTales - The Cursed Prince', 'figures_by_franchise', 34, 3),
    ('nana', 'NANA', 'figures_by_franchise', 35, 3),
    ('sousou_no_frieren', 'Sousou no Frieren', 'figures_by_franchise', 36, 3),
    ('eve', 'Eve', 'figures_by_franchise', 37, 3),
    ('bang_dream_its_mygo', 'Bang Dream! It''s MyGo!!!!!', 'figures_by_franchise', 38, 3),
    ('death_note', 'Death Note', 'figures_by_franchise', 39, 3),
    ('other_franchise', 'Другое', 'figures_by_franchise', 40, 3),
    ('pokemon', 'Pokémon TCG', 'cards_by_game', 1, 3),
    ('yugioh', 'Yu-Gi-Oh!', 'cards_by_game', 2, 3),
    ('one_piece_tcg', 'One Piece TCG', 'cards_by_game', 3, 3),
    ('dragonball_tcg', 'Dragon Ball Super TCG', 'cards_by_game', 4, 3),
    ('digimon', 'Digimon TCG', 'cards_by_game', 5, 3),
    ('naruto_tcg', 'Naruto TCG', 'cards_by_game', 6, 3),
    ('cardfight_vanguard', 'Cardfight!! Vanguard', 'cards_by_game', 7, 3),
    ('weiss_schwarz', 'Weiss Schwarz', 'cards_by_game', 8, 3),
    ('other_tcg', 'Другое', 'cards_by_game', 9, 3),
    ('booster_box', 'Бустер / Бокс', 'cards_by_type', 1, 3),
    ('starter_deck', 'Стартовый набор', 'cards_by_type', 2, 3),
    ('singles', 'Синглы', 'cards_by_type', 3, 3),
    ('manga_volume', 'Манга (том)', 'manga_by_type', 1, 3),
    ('light_novel', 'Ранобэ', 'manga_by_type', 2, 3),
    ('manhwa_manhua', 'Манхва / Маньхуа', 'manga_by_type', 3, 3),
    ('artbook', 'Артбук', 'manga_by_type', 4, 3),
    ('special_edition', 'Коллекционное издание', 'manga_by_type', 5, 3),
    ('shounen', 'Сёнен', 'manga_by_genre', 1, 3),
    ('shoujo', 'Сёдзё', 'manga_by_genre', 2, 3),
    ('seinen', 'Сейнен', 'manga_by_genre', 3, 3),
    ('josei', 'Джосей', 'manga_by_genre', 4, 3),
    ('publisher_kodansha', 'Kodansha', 'manga_by_publisher', 1, 3),
    ('publisher_shueisha', 'Shueisha', 'manga_by_publisher', 2, 3),
    ('publisher_shogakukan', 'Shogakukan', 'manga_by_publisher', 3, 3),
    ('publisher_kadokawa', 'Kadokawa', 'manga_by_publisher', 4, 3),
    ('publisher_square_enix', 'Square Enix', 'manga_by_publisher', 5, 3),
    ('publisher_yen_press', 'Yen Press', 'manga_by_publisher', 6, 3),
    ('publisher_seven_seas', 'Seven Seas', 'manga_by_publisher', 7, 3),
    ('publisher_istari_comics', 'Истари Комикс', 'manga_by_publisher', 8, 3),
    ('publisher_xl_media', 'XL Media', 'manga_by_publisher', 9, 3),
    ('publisher_azbuka', 'Азбука', 'manga_by_publisher', 10, 3),
    ('publisher_other', 'Другое', 'manga_by_publisher', 11, 3);

DO $$
DECLARE current_depth INTEGER;
BEGIN
    FOR current_depth IN 0..3 LOOP
        INSERT INTO category (name, parent_id, slug, display_order)
        SELECT d.name, parent.id, d.slug, d.display_order
        FROM desired_category d
        LEFT JOIN category parent ON parent.slug = d.parent_slug
        WHERE d.depth = current_depth
        ON CONFLICT (slug) DO UPDATE SET
            name = EXCLUDED.name,
            parent_id = EXCLUDED.parent_id,
            display_order = EXCLUDED.display_order;
    END LOOP;
END
$$;

-- Existing condition categories become the USED product property.
UPDATE product p
SET used = TRUE
WHERE EXISTS (
    SELECT 1 FROM product_category pc
    JOIN category c ON c.id = pc.category_id
    WHERE pc.product_id = p.id AND c.slug IN ('used', 'used_cards', 'used_book')
);

-- Map only unambiguous categories from the original, slugless seed.
CREATE TEMP TABLE legacy_alias (
    old_name VARCHAR(255),
    old_parent VARCHAR(255),
    new_slug VARCHAR(100)
) ON COMMIT DROP;
INSERT INTO legacy_alias VALUES
    ('Nendroids', NULL, 'nendoroid'),
    ('Statues', NULL, 'statue_bust'),
    ('Companies', NULL, 'figures_by_manufacturer'),
    ('Figma', NULL, 'figma'),
    ('Franchises', NULL, 'figures_by_franchise'),
    ('Other', NULL, 'irrelevant'),
    ('Sega', 'Companies', 'sega_sfire'),
    ('Taito', 'Companies', 'taito'),
    ('FuRyu', 'Companies', 'furyu'),
    ('Max Factory', 'Companies', 'max_factory'),
    ('Good Smile company', 'Companies', 'gsc'),
    ('Myethos', 'Companies', 'myethos'),
    ('Bandai', 'Companies', 'bandai_banpresto'),
    ('Freeing', 'Companies', 'freeing'),
    ('Othres', 'Companies', 'other_manufacturer'),
    ('Rezero', 'Franchises', 'rezero'),
    ('Vocaloides', 'Franchises', 'vocaloid_miku'),
    ('Titan Attack', 'Franchises', 'attack_on_titan'),
    ('My hero Academy', 'Franchises', 'my_hero_academia'),
    ('Chainsaw man', 'Franchises', 'chainsaw_man'),
    ('Genshin Impact', 'Franchises', 'genshin_impact');

CREATE TEMP TABLE affected_products ON COMMIT DROP AS
SELECT DISTINCT pc.product_id
FROM product_category pc
JOIN category c ON c.id = pc.category_id
WHERE NOT EXISTS (SELECT 1 FROM desired_category d WHERE d.slug = c.slug);

INSERT INTO product_category (product_id, category_id)
SELECT DISTINCT pc.product_id, replacement.id
FROM product_category pc
JOIN category old ON old.id = pc.category_id AND old.slug IS NULL
LEFT JOIN category old_parent ON old_parent.id = old.parent_id
JOIN legacy_alias alias ON alias.old_name = old.name
    AND (alias.old_parent = old_parent.name OR (alias.old_parent IS NULL AND old_parent.id IS NULL))
JOIN category replacement ON replacement.slug = alias.new_slug
WHERE NOT EXISTS (
    SELECT 1 FROM product_category existing
    WHERE existing.product_id = pc.product_id AND existing.category_id = replacement.id
);

DELETE FROM product_category pc
USING category c
WHERE pc.category_id = c.id
  AND NOT EXISTS (SELECT 1 FROM desired_category d WHERE d.slug = c.slug);

UPDATE product p
SET status = 'DELETED'
WHERE p.id IN (SELECT product_id FROM affected_products)
  AND NOT EXISTS (SELECT 1 FROM product_category pc WHERE pc.product_id = p.id)
  AND p.status <> 'DELETED';

-- Detach obsolete rows before deletion so legacy category subtrees cannot block it.
UPDATE category c
SET parent_id = NULL
WHERE NOT EXISTS (SELECT 1 FROM desired_category d WHERE d.slug = c.slug);

DELETE FROM category c
WHERE NOT EXISTS (SELECT 1 FROM desired_category d WHERE d.slug = c.slug);

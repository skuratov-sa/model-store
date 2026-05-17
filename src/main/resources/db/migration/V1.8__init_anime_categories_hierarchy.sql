ALTER TABLE category
    ADD COLUMN IF NOT EXISTS slug VARCHAR(100);

CREATE UNIQUE INDEX IF NOT EXISTS uq_category_slug ON category (slug);

INSERT INTO category (name, parent_id, slug)
VALUES ('Аниме фигурки', NULL, 'anime_figures'),
       ('Аниме карточки', NULL, 'anime_cards'),
       ('Манга / книги', NULL, 'manga_books'),
       ('Нерелевантное', NULL, 'irrelevant')
ON CONFLICT (slug) DO UPDATE SET
    name = EXCLUDED.name,
    parent_id = EXCLUDED.parent_id;

DELETE
FROM category c
WHERE c.slug IS NULL
  AND NOT EXISTS (
    SELECT 1
    FROM product_category pc
    WHERE pc.category_id = c.id
);

INSERT INTO category (name, parent_id, slug)
SELECT c.name, p.id, c.slug
FROM (VALUES
          ('По типу', 'anime_figures', 'figures_by_type'),
          ('По производителю', 'anime_figures', 'figures_by_manufacturer'),
          ('По франшизе', 'anime_figures', 'figures_by_franchise'),
          ('По состоянию', 'anime_figures', 'figures_by_condition'),
          ('По игре / серии', 'anime_cards', 'cards_by_game'),
          ('По типу', 'anime_cards', 'cards_by_type'),
          ('По состоянию', 'anime_cards', 'cards_by_condition'),
          ('По типу', 'manga_books', 'manga_by_type'),
          ('По жанру', 'manga_books', 'manga_by_genre'),
          ('По состоянию', 'manga_books', 'manga_by_condition')
     ) AS c(name, parent_slug, slug)
         JOIN category p ON p.slug = c.parent_slug
ON CONFLICT (slug) DO UPDATE SET
    name = EXCLUDED.name,
    parent_id = EXCLUDED.parent_id;

INSERT INTO category (name, parent_id, slug)
SELECT c.name, p.id, c.slug
FROM (VALUES
          ('Scale Figure', 'figures_by_type', 'scale_figure'),
          ('Nendoroid', 'figures_by_type', 'nendoroid'),
          ('Figma', 'figures_by_type', 'figma'),
          ('Chibi / Deformed', 'figures_by_type', 'chibi'),
          ('Garage Kit (GK)', 'figures_by_type', 'garage_kit'),
          ('Gashapon / Capsule', 'figures_by_type', 'gashapon'),
          ('Statue / Bust', 'figures_by_type', 'statue_bust'),
          ('Plush / Мягкие игрушки', 'figures_by_type', 'plush'),
          ('Акрил / Стенды', 'figures_by_type', 'acrylic_stand'),
          ('NSFW (18+)', 'figures_by_type', 'nsfw_18plus'),
          ('Good Smile Company', 'figures_by_manufacturer', 'gsc'),
          ('Max Factory', 'figures_by_manufacturer', 'max_factory'),
          ('Kotobukiya', 'figures_by_manufacturer', 'kotobukiya'),
          ('Alter', 'figures_by_manufacturer', 'alter'),
          ('Bandai / Banpresto', 'figures_by_manufacturer', 'bandai_banpresto'),
          ('Aniplex', 'figures_by_manufacturer', 'aniplex'),
          ('Sega / S-Fire', 'figures_by_manufacturer', 'sega_sfire'),
          ('Furyu', 'figures_by_manufacturer', 'furyu'),
          ('Taito', 'figures_by_manufacturer', 'taito'),
          ('Freeing', 'figures_by_manufacturer', 'freeing'),
          ('Union Creative', 'figures_by_manufacturer', 'union_creative'),
          ('Myethos', 'figures_by_manufacturer', 'myethos'),
          ('Apex / AniGame', 'figures_by_manufacturer', 'apex_anigame'),
          ('Другой производитель', 'figures_by_manufacturer', 'other_manufacturer'),
          ('Vocaloid / Hatsune Miku', 'figures_by_franchise', 'vocaloid_miku'),
          ('Genshin Impact', 'figures_by_franchise', 'genshin_impact'),
          ('Honkai: Star Rail', 'figures_by_franchise', 'honkai_star_rail'),
          ('Azur Lane', 'figures_by_franchise', 'azur_lane'),
          ('Arknights', 'figures_by_franchise', 'arknights'),
          ('Blue Archive', 'figures_by_franchise', 'blue_archive'),
          ('Naruto / Boruto', 'figures_by_franchise', 'naruto'),
          ('One Piece', 'figures_by_franchise', 'one_piece'),
          ('Demon Slayer', 'figures_by_franchise', 'demon_slayer'),
          ('Attack on Titan', 'figures_by_franchise', 'attack_on_titan'),
          ('Jujutsu Kaisen', 'figures_by_franchise', 'jujutsu_kaisen'),
          ('Chainsaw Man', 'figures_by_franchise', 'chainsaw_man'),
          ('Bleach', 'figures_by_franchise', 'bleach'),
          ('Dragon Ball', 'figures_by_franchise', 'dragon_ball'),
          ('My Hero Academia', 'figures_by_franchise', 'my_hero_academia'),
          ('Evangelion', 'figures_by_franchise', 'evangelion'),
          ('Fate / Stay Night', 'figures_by_franchise', 'fate'),
          ('Re:Zero', 'figures_by_franchise', 'rezero'),
          ('Sword Art Online', 'figures_by_franchise', 'sword_art_online'),
          ('Spy x Family', 'figures_by_franchise', 'spy_x_family'),
          ('Overlord', 'figures_by_franchise', 'overlord'),
          ('One Punch Man', 'figures_by_franchise', 'one_punch_man'),
          ('Fullmetal Alchemist', 'figures_by_franchise', 'fma'),
          ('Sailor Moon', 'figures_by_franchise', 'sailor_moon'),
          ('Hunter x Hunter', 'figures_by_franchise', 'hxh'),
          ('Tokyo Revengers', 'figures_by_franchise', 'tokyo_revengers'),
          ('Lycoris Recoil', 'figures_by_franchise', 'lycoris_recoil'),
          ('Danganronpa', 'figures_by_franchise', 'danganronpa'),
          ('Warhammer', 'figures_by_franchise', 'warhammer'),
          ('Другая франшиза', 'figures_by_franchise', 'other_franchise'),
          ('В наличии', 'figures_by_condition', 'in_stock'),
          ('Предзаказ', 'figures_by_condition', 'preorder'),
          ('Б/у', 'figures_by_condition', 'used'),
          ('Pokémon TCG', 'cards_by_game', 'pokemon'),
          ('Weiss Schwarz', 'cards_by_game', 'weiss_schwarz'),
          ('Cardfight!! Vanguard', 'cards_by_game', 'cardfight_vanguard'),
          ('One Piece TCG', 'cards_by_game', 'one_piece_tcg'),
          ('Dragon Ball Super TCG', 'cards_by_game', 'dragonball_tcg'),
          ('Naruto TCG', 'cards_by_game', 'naruto_tcg'),
          ('Digimon TCG', 'cards_by_game', 'digimon'),
          ('Yu-Gi-Oh!', 'cards_by_game', 'yugioh'),
          ('Другая игра', 'cards_by_game', 'other_tcg'),
          ('Бустер / Бокс', 'cards_by_type', 'booster_box'),
          ('Стартовый набор', 'cards_by_type', 'starter_deck'),
          ('Синглы (отд. карты)', 'cards_by_type', 'singles'),
          ('Graded (PSA/BGS)', 'cards_by_type', 'graded'),
          ('Промо карты', 'cards_by_type', 'promo'),
          ('Новый, запечатанный', 'cards_by_condition', 'sealed'),
          ('Б/у', 'cards_by_condition', 'used_cards'),
          ('Оценённые', 'cards_by_condition', 'graded_cards'),
          ('Манга (том)', 'manga_by_type', 'manga_volume'),
          ('Ранобэ', 'manga_by_type', 'light_novel'),
          ('Артбук', 'manga_by_type', 'artbook'),
          ('Манхва / Маньхуа', 'manga_by_type', 'manhwa_manhua'),
          ('Коллекционное издание', 'manga_by_type', 'special_edition'),
          ('Сёнен', 'manga_by_genre', 'shounen'),
          ('Сёдзё', 'manga_by_genre', 'shoujo'),
          ('Сейнен', 'manga_by_genre', 'seinen'),
          ('Джосей', 'manga_by_genre', 'josei'),
          ('Новая', 'manga_by_condition', 'new_book'),
          ('Б/у', 'manga_by_condition', 'used_book')
     ) AS c(name, parent_slug, slug)
         JOIN category p ON p.slug = c.parent_slug
ON CONFLICT (slug) DO UPDATE SET
    name = EXCLUDED.name,
    parent_id = EXCLUDED.parent_id;

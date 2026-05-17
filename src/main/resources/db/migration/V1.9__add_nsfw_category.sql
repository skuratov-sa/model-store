INSERT INTO category (name, parent_id, slug)
VALUES ('18+', NULL, 'nsfw_adult')
ON CONFLICT (slug) DO UPDATE SET name = EXCLUDED.name;

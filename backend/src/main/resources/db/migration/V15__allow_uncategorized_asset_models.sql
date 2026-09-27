-- A null category is represented as the presentation-only Default category.  The composite
-- tenant foreign key remains in force whenever a category is selected.
ALTER TABLE asset_model ALTER COLUMN category_id DROP NOT NULL;

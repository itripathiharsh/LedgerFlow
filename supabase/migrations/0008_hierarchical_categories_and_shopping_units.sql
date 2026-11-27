-- Reference SQL migration adding category descriptions/hierarchy index and structured shopping list quantities (local vault v6)
alter table if exists public.categories
  add column if not exists description text not null default '';

create index if not exists idx_categories_parent_category_id
  on public.categories (parent_category_id);

alter table if exists public.shopping_lists
  add column if not exists last_converted_tx_id uuid,
  add column if not exists last_converted_signature text not null default '';

alter table if exists public.shopping_items
  add column if not exists quantity_value double precision,
  add column if not exists quantity_unit text not null default '';

-- Reference Supabase schema migration adding quantity labels to shopping items (local vault v5)
alter table if exists public.shopping_items
  add column if not exists quantity_label text not null default '';

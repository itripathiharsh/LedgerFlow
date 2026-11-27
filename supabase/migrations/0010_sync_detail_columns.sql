-- Columns that let cloud sync carry credit limits, exact account types, the category tree and
-- spending purpose. The app works without them, but then those details stay on the device that
-- entered them. Safe to run repeatedly.
--
-- detail_version is 0 on rows written without these columns, so clients know to keep their own
-- values instead of reading the column defaults.

alter table public.accounts
  add column if not exists credit_limit_minor bigint not null default 0,
  add column if not exists account_subtype text not null default '',
  add column if not exists detail_version integer not null default 0;

alter table public.categories
  add column if not exists parent_category_id uuid,
  add column if not exists description text not null default '',
  add column if not exists detail_version integer not null default 0;

alter table public.transactions
  add column if not exists category_ids_csv text not null default '',
  add column if not exists spending_purpose text not null default '',
  add column if not exists detail_version integer not null default 0;

-- Make PostgREST see the new columns immediately.
notify pgrst, 'reload schema';

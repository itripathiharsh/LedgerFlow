-- Reference SQL migration for credit limits, category hierarchy, and multi-category transaction tags
alter table public.accounts
  add column if not exists credit_limit_minor bigint not null default 0;

alter table public.categories
  add column if not exists parent_category_id uuid;

alter table public.transactions
  add column if not exists category_ids_csv text not null default '';

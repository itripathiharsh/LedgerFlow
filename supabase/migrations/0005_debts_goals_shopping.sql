-- Reference Supabase schema for Debts, Savings Goals, and Shopping Lists (local vault v3)
create table if not exists public.debts (
  id uuid primary key,
  user_id uuid not null,
  person_name text not null,
  direction text not null,
  total_amount_minor bigint not null,
  remaining_amount_minor bigint not null,
  currency text not null,
  account_id uuid,
  due_at timestamptz,
  note text not null default '',
  status text not null default 'ACTIVE',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz
);

create table if not exists public.savings_goals (
  id uuid primary key,
  user_id uuid not null,
  name text not null,
  target_amount_minor bigint not null,
  saved_amount_minor bigint not null default 0,
  currency text not null,
  target_date timestamptz,
  icon_key text not null default 'savings',
  color_hex text not null default '#059669',
  linked_account_id uuid,
  status text not null default 'ACTIVE',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz
);

create table if not exists public.shopping_lists (
  id uuid primary key,
  user_id uuid not null,
  title text not null,
  default_category_id uuid,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz
);

create table if not exists public.shopping_items (
  id uuid primary key,
  user_id uuid not null,
  list_id uuid not null,
  name text not null,
  estimated_price_minor bigint not null default 0,
  checked boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz
);

-- Row-Level Security, applied together with the tables so they are never exposed.
alter table public.debts enable row level security;
alter table public.savings_goals enable row level security;
alter table public.shopping_lists enable row level security;
alter table public.shopping_items enable row level security;

drop policy if exists debts_owner_all on public.debts;
create policy debts_owner_all on public.debts
  for all to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

drop policy if exists savings_goals_owner_all on public.savings_goals;
create policy savings_goals_owner_all on public.savings_goals
  for all to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

drop policy if exists shopping_lists_owner_all on public.shopping_lists;
create policy shopping_lists_owner_all on public.shopping_lists
  for all to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

drop policy if exists shopping_items_owner_all on public.shopping_items;
create policy shopping_items_owner_all on public.shopping_items
  for all to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

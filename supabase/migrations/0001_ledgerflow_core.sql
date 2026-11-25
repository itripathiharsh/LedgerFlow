-- LedgerFlow Core Financial Schema (PostgreSQL / Supabase)
-- Follows Supabase Postgres best practices:
--   - bigint minor units for exact currency arithmetic
--   - numeric(20, 8) for fractional investment quantities
--   - timestamptz everywhere with soft-delete tombstones (deleted_at) for offline incremental sync
--   - strict RLS with TO authenticated + (select auth.uid()) caching + composite sync indexes

create table if not exists public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  base_currency text not null default 'USD',
  app_lock_enabled boolean not null default false,
  auto_lock_seconds integer not null default 10 check (auto_lock_seconds >= 0),
  blur_in_switcher boolean not null default true,
  pay_and_track_enabled boolean not null default true,
  updated_at timestamptz not null default now()
);

create table if not exists public.accounts (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  name text not null,
  institution text not null default '',
  account_type text not null check (account_type in ('CHECKING', 'CARD', 'CASH', 'SAVINGS', 'INVESTMENT', 'SHARED')),
  currency text not null default 'USD',
  balance_minor bigint not null default 0,
  last_four text not null default '',
  source_type text not null default 'MANUAL' check (source_type in ('MANUAL', 'OPEN_BANKING', 'SHARED')),
  sync_status text not null default 'OK' check (sync_status in ('OK', 'RECONNECT', 'SYNCING')),
  last_synced_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz
);

create table if not exists public.categories (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  name text not null,
  kind text not null check (kind in ('EXPENSE', 'INCOME', 'TRANSFER')),
  icon_key text not null default 'category',
  color_hex text not null default '#059669',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz
);

create table if not exists public.transactions (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  account_id uuid not null,
  transfer_account_id uuid,
  category_id uuid,
  tx_type text not null check (tx_type in ('EXPENSE', 'INCOME', 'TRANSFER')),
  amount_minor bigint not null,
  currency text not null default 'USD',
  converted_minor bigint,
  converted_currency text,
  merchant text not null,
  note text not null default '',
  labels_csv text not null default '',
  source_tag text not null default 'MANUAL' check (source_tag in ('MANUAL', 'GPAY_AUTO', 'BANK_SYNC', 'PLANNED', 'IMPORT')),
  auto_rule_applied boolean not null default false,
  occurred_at timestamptz not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz
);

create table if not exists public.budgets (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  category_id uuid,
  name text not null,
  limit_minor bigint not null check (limit_minor >= 0),
  currency text not null default 'USD',
  period text not null default 'MONTHLY' check (period in ('WEEKLY', 'MONTHLY', 'YEARLY')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz
);

create table if not exists public.planned_payments (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  account_id uuid,
  category_id uuid,
  title text not null,
  amount_minor bigint not null,
  currency text not null default 'USD',
  tx_type text not null default 'EXPENSE' check (tx_type in ('EXPENSE', 'INCOME')),
  cadence text not null default 'MONTHLY' check (cadence in ('WEEKLY', 'MONTHLY', 'YEARLY')),
  next_due_at timestamptz not null,
  auto_pay boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz
);

create table if not exists public.holdings (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  symbol text not null,
  name text not null,
  asset_class text not null check (asset_class in ('STOCKS', 'CRYPTO', 'FOREX')),
  exchange text not null default '',
  quantity numeric(20, 8) not null default 0,
  avg_cost_minor bigint not null default 0,
  current_price_minor bigint not null default 0,
  currency text not null default 'USD',
  day_change_bps integer not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz
);

create table if not exists public.magic_rules (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  match_pattern text not null,
  target_category_id uuid,
  target_label text not null default '',
  enabled boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz
);

-- Indexes for fast RLS checks & incremental updated_at sync pulls
create index if not exists accounts_user_updated_idx on public.accounts (user_id, updated_at desc);
create index if not exists categories_user_updated_idx on public.categories (user_id, updated_at desc);
create index if not exists transactions_user_updated_idx on public.transactions (user_id, updated_at desc);
create index if not exists transactions_user_occurred_idx on public.transactions (user_id, occurred_at desc) where deleted_at is null;
create index if not exists transactions_account_idx on public.transactions (user_id, account_id) where deleted_at is null;
create index if not exists budgets_user_updated_idx on public.budgets (user_id, updated_at desc);
create index if not exists planned_payments_user_updated_idx on public.planned_payments (user_id, updated_at desc);
create index if not exists holdings_user_updated_idx on public.holdings (user_id, updated_at desc);
create index if not exists magic_rules_user_updated_idx on public.magic_rules (user_id, updated_at desc);

-- Updated-at trigger (SECURITY INVOKER, locked empty search_path)
create or replace function public.set_updated_at()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  new.updated_at := greatest(coalesce(new.updated_at, now()), now());
  return new;
end;
$$;

drop trigger if exists trg_profiles_updated_at on public.profiles;
create trigger trg_profiles_updated_at before update on public.profiles
  for each row execute function public.set_updated_at();

drop trigger if exists trg_accounts_updated_at on public.accounts;
create trigger trg_accounts_updated_at before update on public.accounts
  for each row execute function public.set_updated_at();

drop trigger if exists trg_categories_updated_at on public.categories;
create trigger trg_categories_updated_at before update on public.categories
  for each row execute function public.set_updated_at();

drop trigger if exists trg_transactions_updated_at on public.transactions;
create trigger trg_transactions_updated_at before update on public.transactions
  for each row execute function public.set_updated_at();

drop trigger if exists trg_budgets_updated_at on public.budgets;
create trigger trg_budgets_updated_at before update on public.budgets
  for each row execute function public.set_updated_at();

drop trigger if exists trg_planned_payments_updated_at on public.planned_payments;
create trigger trg_planned_payments_updated_at before update on public.planned_payments
  for each row execute function public.set_updated_at();

drop trigger if exists trg_holdings_updated_at on public.holdings;
create trigger trg_holdings_updated_at before update on public.holdings
  for each row execute function public.set_updated_at();

drop trigger if exists trg_magic_rules_updated_at on public.magic_rules;
create trigger trg_magic_rules_updated_at before update on public.magic_rules
  for each row execute function public.set_updated_at();

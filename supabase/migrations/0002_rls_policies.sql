-- LedgerFlow Strict Row-Level Security Policies
-- Pattern: TO authenticated + (select auth.uid()) = user_id (both USING and WITH CHECK)

alter table public.profiles enable row level security;
alter table public.accounts enable row level security;
alter table public.categories enable row level security;
alter table public.transactions enable row level security;
alter table public.budgets enable row level security;
alter table public.planned_payments enable row level security;
alter table public.holdings enable row level security;
alter table public.magic_rules enable row level security;

-- Profiles (user owns row where id = auth.uid())
drop policy if exists profiles_select_own on public.profiles;
create policy profiles_select_own on public.profiles
  for select to authenticated using ((select auth.uid()) = id);

drop policy if exists profiles_insert_own on public.profiles;
create policy profiles_insert_own on public.profiles
  for insert to authenticated with check ((select auth.uid()) = id);

drop policy if exists profiles_update_own on public.profiles;
create policy profiles_update_own on public.profiles
  for update to authenticated
  using ((select auth.uid()) = id)
  with check ((select auth.uid()) = id);

-- User-owned financial tables
drop policy if exists accounts_owner_all on public.accounts;
create policy accounts_owner_all on public.accounts
  for all to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

drop policy if exists categories_owner_all on public.categories;
create policy categories_owner_all on public.categories
  for all to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

drop policy if exists transactions_owner_all on public.transactions;
create policy transactions_owner_all on public.transactions
  for all to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

drop policy if exists budgets_owner_all on public.budgets;
create policy budgets_owner_all on public.budgets
  for all to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

drop policy if exists planned_payments_owner_all on public.planned_payments;
create policy planned_payments_owner_all on public.planned_payments
  for all to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

drop policy if exists holdings_owner_all on public.holdings;
create policy holdings_owner_all on public.holdings
  for all to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

drop policy if exists magic_rules_owner_all on public.magic_rules;
create policy magic_rules_owner_all on public.magic_rules
  for all to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

-- Row-Level Security for the tables created by 0005. Without it any holder of the publishable key
-- could read and write them. Safe to run repeatedly; needed on databases that ran an earlier 0005.

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

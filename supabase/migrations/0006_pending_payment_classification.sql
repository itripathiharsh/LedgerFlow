-- Optional reference metadata columns for user-classified transactions (raw notification data remains local-only)
alter table public.transactions
  add column if not exists review_status text not null default 'CONFIRMED',
  add column if not exists spending_purpose text not null default '';

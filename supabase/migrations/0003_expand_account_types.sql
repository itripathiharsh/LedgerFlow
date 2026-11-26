-- Reference SQL migration to allow explicit debit/credit card and loan subtypes in Supabase
-- Note: The Android app also maps subtypes cleanly for existing 0001_ledgerflow_core constraints.
alter table public.accounts
  drop constraint if exists accounts_account_type_check;

alter table public.accounts
  add constraint accounts_account_type_check
  check (account_type in ('CHECKING', 'CARD', 'DEBIT_CARD', 'CREDIT_CARD', 'LOAN', 'CASH', 'SAVINGS', 'INVESTMENT', 'SHARED'));

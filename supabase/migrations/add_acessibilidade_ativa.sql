-- MIGRAÇÃO: Adicionar coluna acessibilidade_ativa à tabela installs
-- Executar no Supabase → SQL Editor

ALTER TABLE public.installs
  ADD COLUMN IF NOT EXISTS acessibilidade_ativa boolean DEFAULT false;

-- Comentário para o painel
COMMENT ON COLUMN public.installs.acessibilidade_ativa
  IS 'true se o utilizador concedeu a permissão de Acessibilidade ao NEXUS (permite captura de ecrã remota)';

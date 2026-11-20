# Segurança do painel ALTUS

## Alterações desta branch

- As sessões do painel deixaram de ser persistidas em `localStorage`; a sessão usa `sessionStorage` e termina quando a aba é fechada. Isto é uma redução de persistência, não uma defesa completa contra XSS.
- O painel solicita apenas os campos operacionais necessários e não carrega valores de telefone/senha da tabela `credenciais`.
- A interface mostra apenas metadados dos registos de actividade; não apresenta números, senhas nem valores capturados.
- O CSV contém metadados e contagens. Não contém telefone, senha ou conteúdo de SMS e neutraliza células que possam ser interpretadas como fórmulas.
- Foram retiradas as antigas rotas API de autenticação/dados que continham credenciais de fallback ou validação insegura.
- Foram adicionados cabeçalhos HTTP de segurança.

## Acções obrigatórias no Supabase

1. **Rodar a chave privilegiada exposta.** A chave `service_role` que esteve em `docs/api/data.js` deve ser considerada comprometida. Remover o ficheiro do branch não apaga a chave do histórico Git. Gere uma chave nova no painel Supabase e revogue a antiga depois de migrar os serviços backend legítimos.
2. Nunca colocar uma chave `service_role` em Android, JavaScript do browser, HTML, repositório público ou qualquer aplicação distribuída ao utilizador. Clientes devem usar apenas a chave publicável/anon, com RLS activada.
3. Rever RLS em `installs`, `credenciais`, `sms`, `sinais`, `device_config` e `logs_ia_erros`. As políticas devem permitir leitura/alteração apenas ao utilizador administrador autorizado. Não activar acesso anónimo a dados privados.
4. Confirmar que o utilizador usado no login do painel tem uma função administrativa atribuída e que as políticas RLS a verificam. O código do browser, por si só, não substitui essa autorização.

## Limitação da sessão

A implementação actual usa `sessionStorage` como mitigação intermédia. JavaScript executado por XSS ainda pode ler o token. A solução final é mover o fluxo de sessão para um backend e usar cookies `HttpOnly; Secure; SameSite`, fazendo os pedidos autenticados através desse backend.

## Configuração e validação

- Confirmar que o domínio do painel usa HTTPS.
- Após publicar, testar login, renovação de sessão, logout, filtros, paginação, histórico, diagnóstico e exportação CSV.
- Testar CSV com valores que começam por `=`, `+`, `-`, `@` e caracteres de controlo.
- Não publicar esta branch em produção antes de confirmar as políticas RLS e rodar a chave privilegiada exposta.

## Onde as chaves são usadas neste repositório

- `MainActivity.kt`, `docs/index.html` e `download-site/index.html` usam a chave pública legacy `anon`. Não são a chave privilegiada `service_role`; clientes Android e browser devem usar uma chave publicável.
- `.github/workflows/build-apk.yml` usa `SUPA_SERVICE_KEY` como segredo do GitHub Actions para enviar o APK ao Storage. Mantê-la como segredo de CI é correcto. Se o valor dessa variável for o mesmo que foi exposto em `docs/api/data.js`, substitui-o pela nova chave secreta antes de revogar a antiga.
- O workflow também usa `SUPA_KEY` para uma operação REST normal. Essa variável deve conter uma chave publicável/anon, nunca a chave secreta.

## Migração das chaves

A documentação actual do Supabase recomenda migrar as chaves legacy `anon` e `service_role` para `sb_publishable_...` nos clientes e `sb_secret_...` nos serviços backend. As novas chaves podem coexistir com as antigas durante a migração; não desactives as legacy antes de actualizar todos os consumidores. <https://supabase.com/docs/guides/getting-started/migrating-to-new-api-keys>

A chave privilegiada que apareceu no histórico deve ser tratada como comprometida. A remoção do ficheiro nesta branch não apaga o segredo dos commits antigos. Segue o procedimento oficial de rotação e revogação depois de actualizar os consumidores legítimos: <https://supabase.com/docs/guides/security/rotate-leaked-api-keys>

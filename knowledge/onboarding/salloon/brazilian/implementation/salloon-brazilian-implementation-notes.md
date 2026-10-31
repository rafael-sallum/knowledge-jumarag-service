# Notas de implementação da Sinuca brasileira no Salloon Score

Classificação: `IMPLEMENTATION_NOTE`

Estas notas descrevem o motor esportivo determinístico do Salloon Score. Elas não substituem as regras da CBBS. As Regras da Sinuca e o Regulamento dos Esportes do Bilhar são ingeridos separadamente como fontes `OFFICIAL_RULE`.

## Perfis e compatibilidade

- **Comportamento Salloon:** `brazilian-saloon-v1` é o perfil público atual. `legacy-brazilian-v1` existe para preservar históricos antigos e possui abertura e política de falta diferentes.
- **Evidência:** `packages/sporting-engine/src/domain/rulesets/brazilian/profile.ts`; `saloon-ruleset.test.ts`.
- **Avaliação:** `SPECIALIZES_OFFICIAL_RULE` para o perfil atual; o perfil legado representa comportamento histórico do produto.

## Bolas e progressão da bola da vez

- **Comportamento Salloon:** as sete bolas objetivas valem de 1 a 7; a disponível de menor valor é a bola da vez. Sua conversão lícita a remove e avança para o próximo valor disponível.
- **Evidência:** `brazilian/balls.ts`; `brazilian/projection-engine.ts`; `saloon-ruleset.test.ts`.
- **Relação oficial:** o Artigo 1 das Regras da Sinuca CBBS 2012 define os mesmos valores e o conceito de bola da vez.
- **Avaliação:** `MATCHES_OFFICIAL_RULE`.

## Opção arriscada inicial (“bola com castigo”)

- **Comportamento Salloon:** cada turno do perfil atual começa na fase opcional `RISKY`. O jogador pode declarar uma bola disponível diferente da bola da vez, dispensar a opção, convertê-la sem removê-la ou falhar. A falha credita sete pontos ao adversário e transfere o turno.
- **Evidência:** comandos de arriscada em `commands.ts`; `projection-engine.ts`; testes de arriscada em `saloon-ruleset.test.ts`.
- **Relação oficial:** o Artigo 1 permite uma colorida opcional sujeita a castigo antes da bola da vez; o Artigo 7 fixa a pena técnica em sete pontos.
- **Avaliação:** `MATCHES_OFFICIAL_RULE` para o fluxo de pontuação modelado.

## Bola da vez, Premium e próxima arriscada

- **Comportamento Salloon:** após converter a bola da vez, o produto chama de `PREMIUM` a oportunidade opcional de colorida livre. Convertê-la soma seu valor sem removê-la e abre outra opção arriscada antes da próxima bola da vez. Falhar ou recusar Premium transfere o turno.
- **Evidência:** `projection-engine.ts`; testes de ciclo e falha/recusa em `saloon-ruleset.test.ts`.
- **Relação oficial:** o Artigo 1 descreve uma colorida livre depois da bola da vez e uma colorida subsequente sujeita a castigo.
- **Avaliação:** `SPECIALIZES_OFFICIAL_RULE`; “Premium” e “Arriscada” são termos de produto Salloon.

## Penalidade fixa de sete pontos

- **Comportamento Salloon:** `brazilian-saloon-v1` aceita somente `add-seven-to-opponent`; resultados sem alteração, subtração ou valor personalizado são rejeitados. Falta e troca de turno são registradas na mesma ação correlacionada.
- **Evidência:** `profile.ts`; `command-policy.ts`; `commands.ts#recordFoul`; teste de sete pontos em `saloon-ruleset.test.ts`.
- **Relação oficial:** o Artigo 7 credita sete pontos ao adversário após falta técnica.
- **Avaliação:** `MATCHES_OFFICIAL_RULE` para faltas técnicas. Desclassificação disciplinar fica fora deste motor esportivo.

## Erro e transferência do turno

- **Comportamento Salloon:** um erro normal só é aceito na bola da vez. Ele não gera pontos e transfere imediatamente para a fase arriscada do adversário. Falhas de Arriscada e Premium possuem comandos próprios.
- **Evidência:** `command-policy.ts#brazilianNormalMissAllowed`; `commands.ts#recordMiss`; `saloon-ruleset.test.ts`.
- **Avaliação:** `SPECIALIZES_OFFICIAL_RULE`.

## Retorno das bolas não definitivas

- **Comportamento Salloon:** conversões Arriscada e Premium pontuam, mas não removem a bola objetiva. A conversão definitiva da bola da vez a remove. Correções podem restaurar conjuntos lógicos de bolas presentes/retiradas sem reescrever o histórico.
- **Evidência:** `projection-engine.ts`; `commands.ts#correctTableState`; testes de progressão.
- **Relação oficial:** o Artigo 1 diz que somente a bola da vez convertida licitamente é retirada em definitivo.
- **Avaliação:** `MATCHES_OFFICIAL_RULE` no estado lógico; a escolha física de marca não é modelada.

## Recusa/passagem após falta ou erro de colorida

- **Comportamento Salloon:** falta, falha Arriscada e falha/recusa Premium transferem diretamente ao adversário. Existe troca manual auditada, mas não há fluxo dedicado que preserve a eleição oficial de jogar ou devolver a ação.
- **Evidência:** `commands.ts#switchEvents`, `recordFoul`, comandos de falha Arriscada/Premium e `switchTurn`.
- **Relação oficial:** o Artigo 4 permite ao beneficiado recusar depois de falta ou colorida não convertida.
- **Avaliação:** `SIMPLIFIES_OFFICIAL_RULE` / `REQUIRES_REVIEW`.

## Preta definitiva e última oportunidade Premium

- **Comportamento Salloon:** após converter a preta definitiva, o motor oferece a própria preta como última oportunidade Premium. O frame aguarda conclusão apenas depois de essa oportunidade ser convertida, falhada ou recusada.
- **Evidência:** testes de Premium final em `saloon-ruleset.test.ts`.
- **Avaliação:** `REQUIRES_REVIEW` frente aos Artigos 1 e 9 da CBBS antes de afirmar equivalência exata.

## Encerramento e vantagem inalcançável

- **Comportamento Salloon:** esgotar a mesa gera sugestão de vencedor que exige confirmação; outro comando pode concluir por vantagem inalcançável. A concessão dá o frame ao adversário sem inventar pontos.
- **Evidência:** `projection-types.ts`; `commands.ts#concludeFrameByUnreachableLead`; `commands.ts#concedeFrame`.
- **Relação oficial:** o Artigo 9 define limiares exatos de diferença e partida complementar de preta em caso de empate.
- **Avaliação:** `REQUIRES_REVIEW` para equivalência exata dos limiares e desempate.

## Sinuca física, cantada e reposição

- **Comportamento Salloon:** o motor guarda bolas presentes/retiradas, mas não calcula geometria, sinuca total/parcial, evidência de cantada, obstrução de marca ou posicionamento físico. O operador informa resultados e correções auditadas.
- **Evidência:** tipos de projeção e comandos brasileiros não contêm coordenadas, geometria ou modelo de decisão arbitral.
- **Relação oficial:** os Artigos 3, 5 e 6 especificam esses conceitos.
- **Avaliação:** `SIMPLIFIES_OFFICIAL_RULE` / `REQUIRES_REVIEW`; o JumaRAG não pode afirmar que o Salloon os detectou.


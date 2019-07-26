const functions = require('firebase-functions');

// // Create and Deploy Your First Cloud Functions
// // https://firebase.google.com/docs/functions/write-firebase-functions
//
exports.health = functions.https.onRequest((request, response) => {
  response.send("Firebase is Up!");
});

// Substitui palavras-chave por emoji na chave "text" de mensagens enviadas
// para /messages
exports.emojify =
	functions.database.ref('/chat/{chatroomuid}/messages/{messageuid}/text')
	.onWrite(event => {

	// Eventos de gravação no banco de dados incluem nós de dados novos,
        // modificados, ou deletados. Todos os três tipos de eventos no
        // caminho específico do banco de dados ativam esta cloud function.
        // Para esta função nós queremos apenas emojificar novos nós de banco de dados,
        // então primeiramente checaremos para sair da função previamente
        // caso não haja uma nova mensagem.

        // !event.data.val() é um evento deletado
        // event.data.previous.val() é um evento modificado
        if (!event.data.val() || event.data.previous.val()) {
		console.log("not a new write event");
		return;
        }

        // Agora começaremos a transformação de emoji
	console.log("emojifying!");

        // Obtém o valor da chave 'text' da mensagem
	const originalText = event.data.val();
	const emojifiedText = emojifyText(originalText);

        // Retorna um Promise de JavaScript para atualizar o nó do banco de dados
	return event.data.ref.set(emojifiedText);
	});

// Retorna texto com palavras-chave substituídas por emoji
// substituindo com a expressão regular /.../ig faz uma busca
// case-insensitive (i flag) para todas as ocorrências (g flag) na string
function emojifyText(text) {
    var emojifiedText = text;
    emojifiedText = emojifiedText.replace(/\blol\b/ig, "😂");
    emojifiedText = emojifiedText.replace(/\bcat\b/ig, "😸");
    return emojifiedText;
}


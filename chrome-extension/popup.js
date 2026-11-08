const $ = (id) => document.getElementById(id)

async function render() {
  const { token = '', port = 9000, status = 'not configured' } = await chrome.storage.local.get(['token', 'port', 'status'])
  $('token').value = token
  $('port').value = port
  $('status').textContent = `Status: ${status}`
}

$('save').addEventListener('click', async () => {
  await chrome.storage.local.set({ token: $('token').value.trim(), port: Number($('port').value) })
  setTimeout(render, 800)
})

render()

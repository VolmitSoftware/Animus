// A behavior's Test pane: run the example innkeeper from its click start,
// read what it says on the stage and in the chat, answer its questions, then
// Run again to start over.
// Offline: no fake server needed. The examples workspace is made before filming.
module.exports = async (L) => {
  const f = await L.session({id: '16-dry-run'});
  const ws = await L.examplesWorkspace(f.setup);
  const page = await f.start(`${L.SITE}/${ws}/list/behavior`);
  const h = L.human(f);
  const list = page.getByRole('listbox', {name: 'Behaviors'});
  await list.waitFor();
  await page.waitForTimeout(1500);
  f.mark();
  await h.click(list.getByRole('option', {name: /^Innkeeper/}), {after: 1200});
  await page.getByRole('heading', {name: 'Innkeeper', level: 1}).waitFor();
  const test = page.getByRole('region', {name: 'Test'});
  await h.pick(test.getByRole('combobox', {name: 'Starts'}), 'click', {after: 600});
  await h.click(test.getByRole('button', {name: 'Run'}), {after: 1200});
  const chat = test.getByRole('list', {name: 'Chat'});
  await chat.getByRole('group', {name: 'Answers'}).waitFor();
  await h.result(2000);
  await h.click(chat.getByRole('button', {name: 'Heard any rumours?'}), {after: 1500});
  await h.result(1800);
  // Follow the newest question's first answer while the conversation keeps asking.
  for (let i = 0; i < 2; i++) {
    const answers = chat.getByRole('group', {name: 'Answers'}).last().getByRole('button');
    if (!(await answers.count())) break;
    await h.click(answers.first(), {after: 1500});
  }
  await h.hover(chat.getByRole('listitem').last(), 900);
  await h.result(1800);
  await h.click(test.getByRole('button', {name: 'Run'}), {after: 1200});
  await h.result(1600);
  return L.finish(f, {closing: 'The innkeeper ran in the browser, answers and all'});
};

/** The shell pins its chrome and scrolls the content pane, not the document. */
export function scrollPane(): HTMLElement | null {
  return document.querySelector<HTMLElement>('.content');
}

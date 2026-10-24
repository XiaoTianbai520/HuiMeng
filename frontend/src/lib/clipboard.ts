export async function readClipboardText(): Promise<string> { return navigator.clipboard.readText(); }
export async function readClipboardImage(): Promise<Blob | null> {
  for (const item of await navigator.clipboard.read()) {
    const type = item.types.find(value => value.startsWith("image/"));
    if (type) return item.getType(type);
  }
  return null;
}
export async function readClipboardFiles(): Promise<File[]> {
  const files: File[] = [];
  for (const item of await navigator.clipboard.read()) for (const type of item.types.filter(value => /^(image|audio|video)\//.test(value))) {
    files.push(new File([await item.getType(type)], `pasted-media-${files.length}.${type.split("/")[1]}`, { type }));
  }
  return files;
}
export async function writeClipboardText(text: string): Promise<void> { return navigator.clipboard.writeText(text); }
export async function writeClipboardImage(image: Blob): Promise<void> {
  const bitmap = await createImageBitmap(image);
  try {
    const canvas = document.createElement("canvas");
    canvas.width = bitmap.width;
    canvas.height = bitmap.height;
    const context = canvas.getContext("2d");
    if (!context) throw new Error("当前浏览器无法处理图片");
    context.drawImage(bitmap, 0, 0);
    const png = await new Promise<Blob>((resolve, reject) => canvas.toBlob(value => value ? resolve(value) : reject(new Error("转换图片失败")), "image/png"));
    await navigator.clipboard.write([new ClipboardItem({ "image/png": png })]);
  } finally { bitmap.close(); }
}

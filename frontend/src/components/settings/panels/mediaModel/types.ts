export type MediaProviderModel = {
  id: string;
  label: string;
  type: "text" | "image" | "video" | "audio";
  [key: string]: unknown;
};

export type MediaProvider = {
  fileName: string;
  id: string;
  label: string;
  icon?: string;
  version?: string;
  readme?: string;
  modelsUrl?: string;
  rules?: Record<string, unknown>[];
  models: MediaProviderModel[];
  revision: string;
  loadError?: string;
};

/** 后端 /api/v1/admin/providers/media/* 的原始形态。 */
export type MediaProviderRecord = {
  fileName: string;
  displayName: string;
  version?: string;
  sha256?: string;
  executable?: boolean;
  models?: { id: string; label?: string; type?: string }[];
};

/**
 * 把登记面返回映射成面板沿用的形状。
 *
 * 登记面没有单独的 id/revision：id 取文件名去扩展名（配置以它为键），revision 用来源摘要，
 * 这样编辑弹窗的「保存后回读最新版本」仍能发现并发改动。
 */
export function toMediaProvider(record: MediaProviderRecord): MediaProvider {
  return {
    fileName: record.fileName,
    id: record.fileName.replace(/\.[^.]+$/, ""),
    label: record.displayName,
    version: record.version,
    models: (record.models ?? []).map(model => ({
      id: model.id,
      label: model.label ?? model.id,
      type: (model.type ?? "image") as MediaProviderModel["type"],
    })),
    revision: record.sha256 ?? "",
  };
}

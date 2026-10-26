package cn.huimeng.service;

import cn.huimeng.common.io.FileDownload;
import cn.huimeng.dto.resp.*;
import java.io.InputStream;

/** 项目媒体操作；与工作区文档的快照和修订接口分开。 */
public interface ProjectMediaService {
  ProjectMediaRespDTO upload(
      String projectId, String filename, long length, InputStream content, String requestId);

  PageRespDTO<ProjectMediaRespDTO> list(String projectId, String cursor, int limit);

  FileDownload download(String projectId, String mediaId, String range);

  void delete(String projectId, String mediaId, String requestId);

  /** 仅供内部生成器在占位前校验参考图归属与大小；无 HTTP 路由。 */
  void requireReference(String projectId, String mediaId);

  /** 仅供内部生成器丢弃本次创建的结果；无 HTTP 路由，不依赖已撤销的成员权限。 */
  void discardGenerated(String projectId, String mediaId, String creatorId, String requestId);
}

package cn.huimeng.service;

import cn.huimeng.common.io.FileDownload;
import cn.huimeng.dto.req.FileWriteReqDTO;
import cn.huimeng.dto.resp.SnapshotResultRespDTO;
import cn.huimeng.dto.resp.WriteResultRespDTO;
import java.io.InputStream;

/** 仅已授权成员可读取项目文件；管理员身份本身不授予创作数据访问。 */
public interface ProjectFileService {
  /** 以安全句柄读取有大小上限的文本快照。 */
  SnapshotResultRespDTO snapshot(String projectId, String path, Long maxBytes, String range);

  /** 以安全句柄读取原字节或单一 Range；消费者必须关闭流。 */
  FileDownload download(String projectId, String path, Long maxBytes, String range);

  /** 新建必须 exclusive，覆盖必须提供读取时的字节修订；不创建隐式父目录。 */
  WriteResultRespDTO write(
      String projectId, FileWriteReqDTO input, InputStream content, String requestId);
}

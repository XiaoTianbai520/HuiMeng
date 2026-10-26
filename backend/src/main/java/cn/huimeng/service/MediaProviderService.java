package cn.huimeng.service;

import cn.huimeng.dto.req.MediaProviderReqDTO;
import cn.huimeng.dto.resp.MediaProviderDTOs;
import java.util.List;

/** 自定义媒体供应商文件的管理面：登记、校验、模型清单与删除。 */
public interface MediaProviderService {

  /** 已登记供应商（不下发来源文本）。 */
  List<MediaProviderDTOs.Provider> list();

  /** 只解析来源并返回预览，不落库；供页面在保存前检查。 */
  MediaProviderDTOs.Provider validate(MediaProviderReqDTO input);

  /** 从来源里解析模型清单并登记；同名视为更新。 */
  MediaProviderDTOs.Provider add(MediaProviderReqDTO input, String requestId);

  /** 只刷新某个已登记供应商的模型清单。 */
  MediaProviderDTOs.Provider refreshModels(MediaProviderReqDTO input, String requestId);

  /** 保存模型清单（启用/停用等编辑）。 */
  MediaProviderDTOs.Provider save(MediaProviderReqDTO input, String requestId);

  void delete(MediaProviderReqDTO input, String requestId);
}

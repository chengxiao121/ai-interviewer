package com.xiao.aiagent.controller;

import com.xiao.aiagent.services.MaterialStoreService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 上传资料接口（阶段 7 新增）——JD/简历上传直通路径的 HTTP 面。
 *
 * 为什么独立于 /assistant/chat：上传是"用户点按钮选文件"的确定性动作，
 * 类型与文本在落盘时就已锁定；聊天请求只带 materialIds 引用，
 * 资料全文不进聊天消息——这是上传相对粘贴的核心收益（不污染会话记忆窗口）。
 *
 * 为什么是「纯文本体」而不是 multipart：本项目是 WebFlux 栈（Reactor Netty），
 * Spring MVC 的 MultipartFile 不生效（实测 400 "query parameter not present"）；
 * txt/md 本来就是纯文本，前端 file.text() 读出后直接 POST body 最简单可靠，
 * type/文件名走 query 参数。体积限制由 spring.codec.max-in-memory-size 兜底。
 *
 * 与知识库同步的关系：两者都是"资料进系统"，但知识库进向量库（出题检索用），
 * 上传资料进本地文件存储（分析官画像用），互不相干。
 */
@RequestMapping("/api/materials")
@RestController
public class MaterialController {

    private final MaterialStoreService materialStoreService;

    public MaterialController(MaterialStoreService materialStoreService) {
        this.materialStoreService = materialStoreService;
    }

    /**
     * 上传一份 JD/简历文本资料（txt/md 内容直传 body）。
     *
     * @param content 资料文本（body，UTF-8）
     * @param type    资料类型 JD / RESUME（可选；缺省按文件名推断，推断不出报 400）
     * @param name    原始文件名（可选，仅追溯与类型推断用）
     * @return {materialId, type, fileName, charCount, duplicated}——materialId 供聊天请求引用
     */
    @PostMapping(value = "/upload", consumes = MediaType.TEXT_PLAIN_VALUE)
    public MaterialStoreService.UploadResult upload(@RequestParam(value = "type", required = false) String type,
                                                    @RequestParam(value = "name", required = false) String name,
                                                    @RequestBody String content) {
        return materialStoreService.save(content, type, name);
    }

}

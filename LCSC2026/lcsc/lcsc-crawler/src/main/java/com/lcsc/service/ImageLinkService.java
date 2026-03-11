package com.lcsc.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lcsc.entity.ImageLink;
import com.lcsc.mapper.ImageLinkMapper;
import org.springframework.stereotype.Service;

import java.util.Collection; // ✅ 新增导入
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 图片链接服务层
 *
 * @author lcsc-crawler
 * @since 2024-01-01
 */
@Service
public class ImageLinkService extends ServiceImpl<ImageLinkMapper, ImageLink> {

    /**
     * 分页查询图片链接
     */
    public IPage<ImageLink> getImageLinkPage(int current, int size, String imageName, Integer shopId) {
        Page<ImageLink> page = new Page<>(current, size);
        LambdaQueryWrapper<ImageLink> wrapper = new LambdaQueryWrapper<>();

        if (imageName != null && !imageName.trim().isEmpty()) {
            wrapper.like(ImageLink::getImageName, imageName);
        }
        if (shopId != null) {
            wrapper.eq(ImageLink::getShopId, shopId);
        }

        wrapper.orderByAsc(ImageLink::getShopId)
                .orderByAsc(ImageLink::getImageName);

        return page(page, wrapper);
    }

    /**
     * 根据店铺ID查询图片链接列表
     */
    public List<ImageLink> getImageLinksByShopId(Integer shopId) {
        LambdaQueryWrapper<ImageLink> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ImageLink::getShopId, shopId);
        wrapper.orderByAsc(ImageLink::getImageName);
        return list(wrapper);
    }

    /**
     * 根据图片名称查询图片链接列表
     */
    public List<ImageLink> getImageLinksByImageName(String imageName) {
        LambdaQueryWrapper<ImageLink> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ImageLink::getImageName, imageName);
        wrapper.orderByAsc(ImageLink::getShopId);
        return list(wrapper);
    }

    /**
     * 根据图片名称和店铺ID查询图片链接
     */
    public ImageLink getByImageNameAndShopId(String imageName, Integer shopId) {
        LambdaQueryWrapper<ImageLink> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ImageLink::getImageName, imageName);
        wrapper.eq(ImageLink::getShopId, shopId);
        return getOne(wrapper);
    }

    /**
     * 检查指定图片在指定店铺的链接是否存在
     */
    public boolean existsByImageNameAndShopId(String imageName, Integer shopId) {
        LambdaQueryWrapper<ImageLink> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ImageLink::getImageName, imageName);
        wrapper.eq(ImageLink::getShopId, shopId);
        return count(wrapper) > 0;
    }

    /**
     * 检查指定图片在指定店铺的链接是否存在（排除指定ID）
     */
    public boolean existsByImageNameAndShopId(String imageName, Integer shopId, Integer excludeId) {
        LambdaQueryWrapper<ImageLink> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ImageLink::getImageName, imageName);
        wrapper.eq(ImageLink::getShopId, shopId);
        if (excludeId != null) {
            wrapper.ne(ImageLink::getId, excludeId);
        }
        return count(wrapper) > 0;
    }

    /**
     * 保存或更新图片链接
     */
    public boolean saveOrUpdateImageLink(ImageLink imageLink) {
        if (imageLink.getId() != null) {
            // 更新时检查是否重复
            if (existsByImageNameAndShopId(imageLink.getImageName(),
                    imageLink.getShopId(), imageLink.getId())) {
                throw new RuntimeException("该店铺下已存在相同图片名称的链接");
            }
        } else {
            // 新增时检查是否重复
            if (existsByImageNameAndShopId(imageLink.getImageName(), imageLink.getShopId())) {
                throw new RuntimeException("该店铺下已存在相同图片名称的链接");
            }
        }
        return saveOrUpdate(imageLink);
    }

    /**
     * 删除图片链接
     */
    public boolean deleteImageLinkById(Integer id) {
        return removeById(id);
    }

    /**
     * 批量删除图片链接
     */
    public boolean deleteImageLinkBatch(List<Integer> ids) {
        return removeByIds(ids);
    }

    /**
     * 根据店铺ID删除所有相关图片链接
     */
    public boolean deleteByShopId(Integer shopId) {
        LambdaQueryWrapper<ImageLink> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ImageLink::getShopId, shopId);
        return remove(wrapper);
    }

    /**
     * 根据图片名称删除所有相关链接
     */
    public boolean deleteByImageName(String imageName) {
        LambdaQueryWrapper<ImageLink> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ImageLink::getImageName, imageName);
        return remove(wrapper);
    }

    /**
     * 获取所有图片链接列表
     */
    public List<ImageLink> getAllImageLinks() {
        LambdaQueryWrapper<ImageLink> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByAsc(ImageLink::getShopId)
                .orderByAsc(ImageLink::getImageName);
        return list(wrapper);
    }

    /**
     * 根据关键词搜索图片链接
     */
    public List<ImageLink> searchImageLinks(String keyword) {
        LambdaQueryWrapper<ImageLink> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.trim().isEmpty()) {
            wrapper.and(w -> w.like(ImageLink::getImageName, keyword)
                    .or()
                    .like(ImageLink::getImageLink, keyword));
        }
        wrapper.orderByAsc(ImageLink::getShopId)
                .orderByAsc(ImageLink::getImageName);
        return list(wrapper);
    }

    /**
     * 统计指定店铺的图片链接数量
     */
    public long countByShopId(Integer shopId) {
        LambdaQueryWrapper<ImageLink> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ImageLink::getShopId, shopId);
        return count(wrapper);
    }

    /**
     * 获取图片链接统计信息
     */
    public long getTotalImageLinkCount() {
        return count();
    }

    /**
     * 批量保存或更新图片链接
     * ✅ 修复：参数类型改为 Collection 以匹配父类定义，解决 Override 报错
     */
    @Override
    public boolean saveOrUpdateBatch(Collection<ImageLink> imageLinks) {
        // 验证每个图片链接的唯一性
        for (ImageLink imageLink : imageLinks) {
            if (imageLink.getId() != null) {
                if (existsByImageNameAndShopId(imageLink.getImageName(),
                        imageLink.getShopId(), imageLink.getId())) {
                    throw new RuntimeException("该店铺下已存在相同图片名称的链接: " + imageLink.getImageName());
                }
            } else {
                if (existsByImageNameAndShopId(imageLink.getImageName(), imageLink.getShopId())) {
                    throw new RuntimeException("该店铺下已存在相同图片名称的链接: " + imageLink.getImageName());
                }
            }
        }
        // 调用父类的批量保存
        return super.saveOrUpdateBatch(imageLinks);
    }

    /**
     * 根据图片名称前缀查询
     */
    public List<ImageLink> getImageLinksByImageNamePrefix(String prefix) {
        LambdaQueryWrapper<ImageLink> wrapper = new LambdaQueryWrapper<>();
        if (prefix != null && !prefix.trim().isEmpty()) {
            wrapper.likeRight(ImageLink::getImageName, prefix);
        }
        wrapper.orderByAsc(ImageLink::getShopId)
                .orderByAsc(ImageLink::getImageName);
        return list(wrapper);
    }

    /**
     * 检查图片链接URL是否有效（简单检查）
     */
    public boolean isValidImageLink(String imageLink) {
        if (imageLink == null || imageLink.trim().isEmpty()) {
            return false;
        }
        // 简单的URL格式检查
        return imageLink.startsWith("http://") || imageLink.startsWith("https://");
    }

    /**
     * 批量获取指定店铺的"无图"链接映射
     * @param shopIds 店铺ID列表
     * @return Map<ShopId, ImageUrl>
     */
    public Map<Integer, String> getNoImageLinksMap(List<Integer> shopIds) {
        if (shopIds == null || shopIds.isEmpty()) {
            return new HashMap<>();
        }

        // 查询这些店铺下，图片名称包含"无图"的链接
        LambdaQueryWrapper<ImageLink> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(ImageLink::getShopId, shopIds);
        // 约定：名称包含"无图"或者是"无图.jpg"
        wrapper.like(ImageLink::getImageName, "no-image.jpg");

        List<ImageLink> links = list(wrapper);

        // 转换为 Map，如果一个店铺有多个无图，取第一个
        Map<Integer, String> resultMap = new HashMap<>();
        for (ImageLink link : links) {
            if (!resultMap.containsKey(link.getShopId()) && isValidImageLink(link.getImageLink())) {
                resultMap.put(link.getShopId(), link.getImageLink());
            }
        }
        return resultMap;
    }
}
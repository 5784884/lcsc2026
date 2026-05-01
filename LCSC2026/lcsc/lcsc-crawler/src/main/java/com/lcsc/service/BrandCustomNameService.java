package com.lcsc.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lcsc.entity.BrandCustomName;
import com.lcsc.mapper.BrandCustomNameMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class BrandCustomNameService extends ServiceImpl<BrandCustomNameMapper, BrandCustomName> {

    public Map<String, String> getCustomNameMap() {
        return list().stream()
                .filter(b -> b.getCustomName() != null && !b.getCustomName().isEmpty() && b.getOriginalName() != null)
                .collect(Collectors.toMap(
                        b -> b.getOriginalName().trim().toLowerCase(),
                        BrandCustomName::getCustomName,
                        (a, b) -> a));
    }

    public void saveCustomName(String originalName, String customName, Integer discountScheme) {
        if (originalName == null || originalName.trim().isEmpty()) return;

        // 🌟 核心：保存进数据库前，必须去除首尾多余的空格，保持数据纯净
        String trimmedName = originalName.trim();
        LambdaQueryWrapper<BrandCustomName> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BrandCustomName::getOriginalName, trimmedName);

        BrandCustomName existing = getOne(wrapper);
        if (existing == null) {
            existing = new BrandCustomName();
            existing.setOriginalName(trimmedName);
        }
        existing.setCustomName(customName);
        existing.setDiscountScheme(discountScheme != null ? discountScheme : 0);
        existing.setUpdatedAt(LocalDateTime.now());
        saveOrUpdate(existing);
    }

    public List<BrandCustomName> getAllWithFilter(String keyword, Boolean edited) {
        LambdaQueryWrapper<BrandCustomName> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.and(w -> w.like(BrandCustomName::getOriginalName, keyword)
                    .or().like(BrandCustomName::getCustomName, keyword));
        }
        if (Boolean.TRUE.equals(edited)) {
            // 🌟 补充修复：有名字 或 有打折方案 都算已编辑
            wrapper.and(w -> w.isNotNull(BrandCustomName::getCustomName).ne(BrandCustomName::getCustomName, "")
                    .or().gt(BrandCustomName::getDiscountScheme, 0));
        } else if (Boolean.FALSE.equals(edited)) {
            wrapper.and(w -> w.isNull(BrandCustomName::getCustomName).or().eq(BrandCustomName::getCustomName, ""))
                    .and(w -> w.isNull(BrandCustomName::getDiscountScheme).or().eq(BrandCustomName::getDiscountScheme, 0));
        }
        return list(wrapper);
    }

    public Map<String, BrandCustomName> getBrandConfigMap() {
        return list().stream()
                .filter(b -> b.getOriginalName() != null)
                .collect(Collectors.toMap(
                        b -> b.getOriginalName().trim().toLowerCase(),
                        b -> b,
                        (a, b) -> a));
    }
}
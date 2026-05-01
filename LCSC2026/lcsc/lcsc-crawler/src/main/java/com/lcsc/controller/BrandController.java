package com.lcsc.controller;

import com.lcsc.common.Result;
import com.lcsc.entity.BrandCustomName;
import com.lcsc.service.BrandCustomNameService;
import com.lcsc.service.ProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/brands")
@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:5174", "http://localhost:5177", "http://127.0.0.1:5177"})
public class BrandController {

    @Autowired
    private BrandCustomNameService brandCustomNameService;

    @Autowired
    private ProductService productService;

    /**
     * 获取品牌列表（含自定义名称），支持关键词搜索和已编辑/未编辑筛选
     */
    @GetMapping("/list")
    public Result<List<Map<String, Object>>> getBrandList(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean edited) {

        // 获取所有原始品牌
        List<String> allOriginalBrands = productService.getAllBrands();

        // 🌟 修复2：构建忽略大小写和首尾空格的强力映射 Map
        Map<String, BrandCustomName> customMap = brandCustomNameService.list().stream()
                .filter(b -> b.getOriginalName() != null)
                .collect(Collectors.toMap(
                        b -> b.getOriginalName().trim().toLowerCase(),
                        b -> b,
                        (a, b) -> a));

        List<Map<String, Object>> result = new ArrayList<>();
        for (String original : allOriginalBrands) {
            if (original == null) continue;

            // 🌟 使用清洗后的键去匹配
            BrandCustomName custom = customMap.get(original.trim().toLowerCase());
            String customName = custom != null ? custom.getCustomName() : null;
            Integer scheme = custom != null && custom.getDiscountScheme() != null ? custom.getDiscountScheme() : 0;

            // 🌟 修复1：只要设置了自定义名称 或者 设置了打折方案，都算"已编辑"！
            boolean isEdited = (customName != null && !customName.isEmpty()) || scheme > 0;

            // 筛选
            if (Boolean.TRUE.equals(edited) && !isEdited) continue;
            if (Boolean.FALSE.equals(edited) && isEdited) continue;

            // 关键词搜索
            if (keyword != null && !keyword.isEmpty()) {
                boolean matchOriginal = original.toLowerCase().contains(keyword.toLowerCase());
                boolean matchCustom = customName != null && customName.toLowerCase().contains(keyword.toLowerCase());
                if (!matchOriginal && !matchCustom) continue;
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("originalName", original);
            item.put("customName", customName != null ? customName : "");
            item.put("discountScheme", scheme);
            item.put("edited", isEdited);
            result.add(item);
        }

        return Result.success(result);
    }

    /**
     * 保存品牌自定义名称
     */
    @PostMapping("/save")
    public Result<String> saveCustomName(@RequestBody Map<String, Object> body) {
        String originalName = (String) body.get("originalName");
        String customName = body.get("customName") != null ? (String) body.get("customName") : "";
        Integer discountScheme = body.get("discountScheme") != null ? ((Number) body.get("discountScheme")).intValue() : 0;
        if (originalName == null || originalName.trim().isEmpty()) {
            return Result.error("原品牌名称不能为空");
        }
        brandCustomNameService.saveCustomName(originalName, customName, discountScheme);
        return Result.success("保存成功");
    }

    /**
     * 获取品牌下拉选项（优先显示自定义名称）
     */
    @GetMapping("/options")
    public Result<List<Map<String, String>>> getBrandOptions() {
        List<String> allOriginalBrands = productService.getAllBrands();

        // 🌟 同样在这里加上防爆盾
        Map<String, String> customMap = brandCustomNameService.list().stream()
                .filter(b -> b.getOriginalName() != null && b.getCustomName() != null && !b.getCustomName().isEmpty())
                .collect(Collectors.toMap(
                        b -> b.getOriginalName().trim().toLowerCase(),
                        BrandCustomName::getCustomName,
                        (a, b) -> a));

        List<Map<String, String>> options = allOriginalBrands.stream().map(original -> {
            Map<String, String> opt = new LinkedHashMap<>();
            opt.put("value", original);
            if (original == null) {
                opt.put("label", "");
                return opt;
            }
            String custom = customMap.get(original.trim().toLowerCase());
            opt.put("label", (custom != null && !custom.isEmpty()) ? custom : original);
            return opt;
        }).collect(Collectors.toList());

        return Result.success(options);
    }
}
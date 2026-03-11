package com.lcsc.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lcsc.entity.CategoryLevel3Code;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import java.util.List;

/**
 * 三级分类 Mapper 接口 - 增强版
 */
@Mapper
public interface CategoryLevel3CodeMapper extends BaseMapper<CategoryLevel3Code> {

    /**
     * 🔥 核心修复：关联查询所有分类层级的名称
     * 确保 t3.category_level3_name 被选中并映射
     */
    @Select("SELECT " +
            "t3.*, " +
            "t3.category_level3_name AS categoryLevel3Name, " +
            "t2.category_level2_name AS categoryLevel2Name, " +
            "t1.category_level1_name AS categoryLevel1Name " +
            "FROM category_level3_codes t3 " +
            "LEFT JOIN category_level2_codes t2 ON t3.category_level2_id = t2.id " +
            "LEFT JOIN category_level1_codes t1 ON t3.category_level1_id = t1.id " +
            "ORDER BY t3.id DESC")
    List<CategoryLevel3Code> selectAllWithNames();
}
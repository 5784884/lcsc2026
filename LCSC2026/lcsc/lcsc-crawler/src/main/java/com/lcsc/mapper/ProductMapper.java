package com.lcsc.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lcsc.entity.Product;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 产品信息 Mapper 接口
 *
 * @author lcsc-crawler
 * @since 2024-01-01
 */
@Mapper
public interface ProductMapper extends BaseMapper<Product> {

    /**
     * 分页查询产品，并关联查询三级分类、二级分类、一级分类的名称
     */
    @Select("<script>" +
            "SELECT p.*, " +
            "c1.category_level1_name as categoryLevel1Name, " +
            "c2.category_level2_name as categoryLevel2Name, " +
            "c3.category_level3_name as categoryLevel3Name " +
            "FROM products p " +
            "LEFT JOIN category_level1_codes c1 ON p.category_level1_id = c1.id " +
            "LEFT JOIN category_level2_codes c2 ON p.category_level2_id = c2.id " +
            "LEFT JOIN category_level3_codes c3 ON p.category_level3_id = c3.id " +
            "<where>" +
            "  <if test='productCode != null and productCode != \"\"'> AND p.product_code LIKE CONCAT('%', #{productCode}, '%') </if>" +
            "  <if test='brand != null and brand != \"\"'> AND p.brand = #{brand} </if>" +
            "  <if test='model != null and model != \"\"'> AND p.model LIKE CONCAT('%', #{model}, '%') </if>" +
            "  <if test='categoryLevel3Id != null'> AND p.category_level3_id = #{categoryLevel3Id} </if>" +
            "</where>" +
            "</script>")
    IPage<Product> selectProductCustomPage(Page<Product> page,
                                           @Param("productCode") String productCode,
                                           @Param("brand") String brand,
                                           @Param("model") String model,
                                           @Param("categoryLevel3Id") Integer categoryLevel3Id);

    /**
     * 根据二级分类ID删除产品(覆盖模式)
     * @param catalogId 二级分类ID
     * @return 删除的记录数
     */
    @Delete("DELETE FROM products WHERE category_level2_id = #{catalogId}")
    int deleteByCatalogId(@Param("catalogId") Integer catalogId);

}
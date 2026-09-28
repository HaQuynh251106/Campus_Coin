package com.campuscoin.category.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.campuscoin.category.entity.Category;
import com.campuscoin.category.entity.CategoryType;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    @Query("""
            SELECT c FROM Category c
             WHERE c.userId = :userId OR c.userId IS NULL
             ORDER BY c.type ASC, c.sortOrder ASC, c.userId ASC, c.id ASC
            """)
    List<Category> findVisibleToUser(@Param("userId") Long userId);

    Optional<Category> findByIdAndUserId(Long id, Long userId);

    @Query("""
            SELECT c FROM Category c
             WHERE c.id = :id AND (c.userId = :userId OR c.userId IS NULL)
            """)
    Optional<Category> findVisibleById(@Param("id") Long id, @Param("userId") Long userId);

    boolean existsByUserIdAndTypeAndNameAndIdNot(Long userId, CategoryType type, String name,
                                                 Long excludedId);

    boolean existsByUserIdIsNullAndTypeAndName(CategoryType type, String name);

    @Query("""
            SELECT c FROM Category c
             WHERE c.userId IS NULL
             ORDER BY c.type ASC, c.sortOrder ASC, c.id ASC
            """)
    List<Category> findAllDefaults();

    @Query("""
            SELECT c FROM Category c
             WHERE c.id = :id AND c.userId IS NULL
            """)
    Optional<Category> findDefaultById(@Param("id") Long id);

    @Query("""
            SELECT c.type FROM Category c
             WHERE c.id = :id AND c.userId IS NULL
            """)
    Optional<CategoryType> findDefaultCategoryTypeById(@Param("id") Long id);

    @Query("""
            SELECT c FROM Category c
             WHERE c.userId IS NULL AND c.type = :type AND c.name = :name
             ORDER BY c.id ASC
            """)
    List<Category> findDefaultsByTypeAndName(@Param("type") CategoryType type,
                                             @Param("name") String name);
}

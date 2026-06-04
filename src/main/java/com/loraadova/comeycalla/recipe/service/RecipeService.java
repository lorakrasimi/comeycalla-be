package com.loraadova.comeycalla.recipe.service;

import com.loraadova.comeycalla.auth.security.CurrenUserService;
import com.loraadova.comeycalla.common.CloudinaryService;
import com.loraadova.comeycalla.recipe.dto.RecipeRequest;
import com.loraadova.comeycalla.recipe.dto.RecipeResponse;
import com.loraadova.comeycalla.recipe.entity.RecipeEntity;
import com.loraadova.comeycalla.recipe.entity.RecipeIngredient;
import com.loraadova.comeycalla.recipe.entity.RecipeStep;
import com.loraadova.comeycalla.recipe.entity.Tag;
import com.loraadova.comeycalla.recipe.mapper.RecipeMapper;
import com.loraadova.comeycalla.recipe.repository.RecipeIngredientRepository;
import com.loraadova.comeycalla.recipe.repository.RecipeRepository;
import com.loraadova.comeycalla.recipe.repository.RecipeStepRepository;
import com.loraadova.comeycalla.recipe.repository.TagRepository;
import com.loraadova.comeycalla.user.entity.UserEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@Service
public class RecipeService {
    @Autowired
    CurrenUserService currenUserService;

    @Autowired
    RecipeRepository recipeRepository;

    @Autowired
    RecipeMapper recipeMapper;

    @Autowired
    TagRepository tagRepository;

    @Autowired
    private RecipeIngredientRepository recipeIngredientRepository;

    @Autowired
    private RecipeStepRepository recipeStepRepository;

    @Autowired
    private CloudinaryService cloudinaryService;

    @PersistenceContext
    private EntityManager entityManager;


    public RecipeResponse createRecipe(RecipeRequest request, MultipartFile image) {
        RecipeEntity newRecipeEntity = this.recipeMapper.toEntity(request);
        newRecipeEntity.setUser(this.getCurrentUser());

        String imageUrl = null;

        if (image != null && !image.isEmpty()) {
            imageUrl = this.cloudinaryService.uploadImage(image);
        } else if (request.getImg() != null && !request.getImg().isBlank()) {
            imageUrl = request.getImg();
        }

        if (imageUrl != null) {
            newRecipeEntity.setImg(imageUrl);
        }

        if (request.getIngredients() != null) setIngredients(request, newRecipeEntity);
        if (request.getSteps() != null) setSteps(request, newRecipeEntity);
        if (request.getTags() != null) setTags(request, newRecipeEntity);

        RecipeEntity saverdRecipeEntity = this.recipeRepository.save(newRecipeEntity);
        return this.recipeMapper.toResponse(saverdRecipeEntity);
    }

    public RecipeResponse findRecipeById(Long id) {
        RecipeEntity recipeEntity = recipeRepository.findRecipeByIdAndUser_Id(id, this.getCurrentUser().getId()).orElseThrow(() -> new RuntimeException("Receta no encontrada"));

        return this.recipeMapper.toResponse(recipeEntity);
    }

    @Transactional
    public RecipeResponse updateRecipe(Long id, RecipeRequest request, MultipartFile image) {
        RecipeEntity recipeEntity = findCurrentUserRecipe(id);

        updateBasicFields(recipeEntity, request);
        replaceRecipeChildren(recipeEntity, request);

        // If image is null = no changes
        if (image != null) {
            if (image.isEmpty()) {
                // Delete image
                recipeEntity.setImg(null);
            } else {
                // New image
                String imageUrl = cloudinaryService.uploadImage(image);
                recipeEntity.setImg(imageUrl);
            }
        }


        return recipeMapper.toResponse(recipeRepository.save(recipeEntity));
    }

    private void setTags(RecipeRequest request, RecipeEntity recipeEntity) {
        for (String tagName : request.getTags()) {
            Tag tag = tagRepository.findByNameIgnoreCase(tagName).orElseGet(() -> {
                Tag newTag = new Tag();
                newTag.setName(tagName);
                return tagRepository.save(newTag);
            });

            recipeEntity.getTags().add(tag);
        }

    }

    private void setSteps(RecipeRequest request, RecipeEntity recipeEntity) {
        for (int i = 0; i < request.getSteps().size(); i++) {
            RecipeStep step = recipeMapper.toEntity(request.getSteps().get(i));
            step.setRecipeEntity(recipeEntity);
            step.setStepOrder(i + 1);
            recipeEntity.getSteps().add(step);
        }
    }

    private void setIngredients(RecipeRequest request, RecipeEntity recipeEntity) {
        for (int i = 0; i < request.getIngredients().size(); i++) {
            RecipeIngredient ingredient = recipeMapper.toEntity(request.getIngredients().get(i));
            ingredient.setRecipeEntity(recipeEntity);
            ingredient.setPosition(i + 1);
            recipeEntity.getIngredients().add(ingredient);
        }
    }

    private UserEntity getCurrentUser() {
        return this.currenUserService.getCurrentUser();
    }

    private RecipeEntity findCurrentUserRecipe(Long id) {
        return recipeRepository.findRecipeByIdAndUser_Id(id, getCurrentUser().getId()).orElseThrow(() -> new RuntimeException("Receta no encontrada"));
    }

    private void updateBasicFields(RecipeEntity recipeEntity, RecipeRequest request) {
        recipeEntity.setTitle(request.getTitle());
        recipeEntity.setDescription(request.getDescription());
        recipeEntity.setCookingTime(request.getCookingTime());
        recipeEntity.setServings(request.getServings());
        recipeEntity.setDifficulty(request.getDifficulty());
        recipeEntity.setCategory(request.getCategory());
    }

    private void replaceRecipeChildren(RecipeEntity recipeEntity, RecipeRequest request) {
        clearRecipeChildren(recipeEntity);

        if (request.getIngredients() != null) setIngredients(request, recipeEntity);
        if (request.getSteps() != null) setSteps(request, recipeEntity);
        if (request.getTags() != null) setTags(request, recipeEntity);

    }

    private void clearRecipeChildren(RecipeEntity recipeEntity) {
        recipeEntity.getIngredients().clear();
        recipeEntity.getSteps().clear();
        recipeEntity.getTags().clear();

        recipeIngredientRepository.deleteByRecipeEntityId(recipeEntity.getId());
        recipeStepRepository.deleteByRecipeEntityId(recipeEntity.getId());

        entityManager.flush();
    }


    @Transactional
    public void deleteRecipe(Long id) {
        this.recipeRepository.deleteRecipeByIdAndUser_Id(id, this.getCurrentUser().getId());
    }

    public List<RecipeResponse> getLastRecipes() {
        List<RecipeEntity> recipeEntityList = this.recipeRepository.findTop10ByUserId(this.getCurrentUser().getId());
        List<RecipeResponse> recipeResponseList = new ArrayList<>();
        recipeEntityList.forEach(recipe -> recipeResponseList.add(this.recipeMapper.toResponse(recipe)));
        return recipeResponseList;
    }

    public RecipeResponse getRandomRecipe() {
        List<RecipeEntity> result = this.recipeRepository.findByUserId(this.getCurrentUser().getId());

        if (result.isEmpty()) {
            return new RecipeResponse();
        }

        int index = new Random().nextInt(result.size());
        RecipeEntity randomRecipeEntity = result.get(index);

        return this.recipeMapper.toResponse(randomRecipeEntity);
    }

    public Page<RecipeResponse> getAllRecipes(
            String search,
            String category,
            Integer maxTime,
            Pageable pageable
    ) {
        Long userId = this.getCurrentUser().getId();

        return recipeRepository
                .findRecipesFiltered(userId, search, category, maxTime, pageable)
                .map(recipeMapper::toResponse);
    }

    public List<String> findCategories() {
        Long userId = this.getCurrentUser().getId();
        List<RecipeEntity> recipeEntityList = this.recipeRepository.findByUserId(userId);

        return recipeEntityList.stream()
                .map(RecipeEntity::getCategory)
                .filter(category -> category != null && !category.isBlank())
                .distinct()
                .toList();
    }

    public int getRecipesCountByUser(Long userId){
        return this.recipeRepository.countByUserId(userId);
    }

}


package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private FollowServiceImpl followService;

    // 首页展示热门博客
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
//        records.forEach(blog ->{
//            Long userId = blog.getUserId();
//            User user = userService.getById(userId);
//            blog.setName(user.getNickName());
//            blog.setIcon(user.getIcon());
//        });
//        records.forEach(this::queryBlogUser);
        // 判断当前用户是否点赞该博客
        records.forEach(blog ->{
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    // 点击后展示博客详情
    @Override
    public Result queryBlogById(Long id) {
        // 1. 查询blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在！");
        }
        // 2. 查询blog作者信息
        queryBlogUser(blog);
        // 判断当前用户是否点赞该博客
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    /*判断当前用户是否点赞该博客,设置blog的isLike属性供前端使用*/
    private void isBlogLiked(Blog blog) {
        // 0. 获取登录用户
        UserDTO user = UserHolder.getUser();
        // 用户未登录，直接返回
        if (user == null) return;
        // 1. 判断当前登录用户是否已经点赞
        Long userId = user.getId();
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    // 查询blog作者信息
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
    //点赞功能核心业务，已点赞则移除，操作redis和数据库
    @Override
    public Result likeBlog(Long id) {
        // 1. 判断当前登录用户是否已经点赞
        Long userId = UserHolder.getUser().getId();
        String key = BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        // 3. 如果未点赞，则进行点赞
        if (score == null){
            // 3.1. 数据库点赞数 + 1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            // 3.2 redis保存用户点赞信息
            if (isSuccess) stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
        }else {  // 2. 如果已经点赞，则取消点赞
            // 2.1 数据库点赞-1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            // 2.2 redis取消保存用户点赞信息
            if (isSuccess) stringRedisTemplate.opsForZSet().remove(key, userId.toString());
        }
        return Result.ok();
    }

    // --点赞排行榜--   查询top5点赞用户并返回 zrange key 0 4
    @Override
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY + id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        // 2. 解析用户id
        if (top5 == null || top5.isEmpty()) return Result.ok(Collections.emptyList());
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        // 3. 根据id查询用户  封装用户，用户数据脱敏
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids).last("ORDER BY FIELD(id, " + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        // end. 返回用户
        return Result.ok(userDTOS);
    }

    // 保存blog并推送至粉丝消息
    @Override
    public Result saveBlog(Blog blog) {
        // 1. 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 2. 保存blog  保存blog id至数据库
        boolean isSuccess = save(blog);
        // 保存失败
        if (!isSuccess) return Result.fail("发布失败！");
        // 3. 查询当前用户所有粉丝
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        // 4. 推送blog id至所有粉丝
        for (Follow follow : follows) {
            Long userId = follow.getUserId();
            String key = FEED_KEY + userId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        // 5. 返回id
        return Result.ok(blog.getId());
    }
    // 关注页blog分页显示
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 1. 获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key = FEED_KEY + userId;
        // 2. 查询收件箱
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 5);
        // 没有数据
        if (typedTuples == null || typedTuples.isEmpty()) return Result.ok();
        // 3. 解析数据：获取收件箱中的blog id、minTime时间戳、offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples){
            // 3.1 获取blog id
            ids.add(Long.valueOf(typedTuple.getValue()));
            // 3.2 获取时间戳
            long time = typedTuple.getScore().longValue();
            if (minTime == time) os++;
            else {
                minTime = time;
                os = 1;
            }
        }
        // 4. 根据blog id查询blog
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id, " + idStr + ")").list();
        for (Blog blog : blogs) {
            // 4.1. 查询blog作者信息
            queryBlogUser(blog);
            // 4.2 判断当前用户是否点赞该博客
            isBlogLiked(blog);
        }

        // 5. 封装并返回
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setOffset(os);
        scrollResult.setMinTime(minTime);

        return Result.ok(scrollResult);
    }
}

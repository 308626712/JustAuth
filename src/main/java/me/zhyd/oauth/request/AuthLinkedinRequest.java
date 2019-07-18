package me.zhyd.oauth.request;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import me.zhyd.oauth.config.AuthConfig;
import me.zhyd.oauth.config.AuthSource;
import me.zhyd.oauth.exception.AuthException;
import me.zhyd.oauth.model.*;
import me.zhyd.oauth.url.AuthLinkedinUrlBuilder;
import me.zhyd.oauth.url.entity.AuthUserInfoEntity;
import me.zhyd.oauth.utils.StringUtils;


/**
 * 领英登录
 *
 * @author yadong.zhang (yadong.zhang0415(a)gmail.com)
 * @version 1.0
 * @since 1.8
 */
public class AuthLinkedinRequest extends AuthDefaultRequest {

    public AuthLinkedinRequest(AuthConfig config) {
        super(config, AuthSource.LINKEDIN, new AuthLinkedinUrlBuilder());
    }

    @Override
    protected AuthToken getAccessToken(AuthCallback authCallback) {
        String accessTokenUrl = this.urlBuilder.getAccessTokenUrl(authCallback.getCode());
        return this.getToken(accessTokenUrl);
    }

    @Override
    protected AuthUser getUserInfo(AuthToken authToken) {
        String accessToken = authToken.getAccessToken();
        HttpResponse response = HttpRequest.get(this.urlBuilder.getUserInfoUrl(AuthUserInfoEntity.builder().build()))
                .header("Host", "api.linkedin.com")
                .header("Connection", "Keep-Alive")
                .header("Authorization", "Bearer " + accessToken)
                .execute();
        JSONObject userInfoObject = JSONObject.parseObject(response.body());

        this.checkResponse(userInfoObject);

        // 组装用户名
        String firstName, lastName;
        // 获取firstName
        if (userInfoObject.containsKey("localizedFirstName")) {
            firstName = userInfoObject.getString("localizedFirstName");
        } else {
            firstName = getUserName(userInfoObject, "firstName");
        }
        // 获取lastName
        if (userInfoObject.containsKey("localizedLastName")) {
            lastName = userInfoObject.getString("localizedLastName");
        } else {
            lastName = getUserName(userInfoObject, "lastName");
        }
        String userName = firstName + " " + lastName;

        // 获取用户头像
        String avatar = null;
        JSONObject profilePictureObject = userInfoObject.getJSONObject("profilePicture");
        if (profilePictureObject.containsKey("displayImage~")) {
            JSONArray displayImageElements = profilePictureObject.getJSONObject("displayImage~")
                    .getJSONArray("elements");
            if (null != displayImageElements && displayImageElements.size() > 0) {
                JSONObject largestImageObj = displayImageElements.getJSONObject(displayImageElements.size() - 1);
                avatar = largestImageObj.getJSONArray("identifiers").getJSONObject(0).getString("identifier");
            }
        }

        // 获取用户邮箱地址
        String email = this.getUserEmail(accessToken);
        return AuthUser.builder()
                .uuid(userInfoObject.getString("id"))
                .username(userName)
                .nickname(userName)
                .avatar(avatar)
                .email(email)
                .token(authToken)
                .gender(AuthUserGender.UNKNOWN)
                .source(AuthSource.LINKEDIN)
                .build();
    }

    private String getUserEmail(String accessToken) {
        String email = null;
        HttpResponse emailResponse = HttpRequest.get("https://api.linkedin.com/v2/emailAddress?q=members&projection=(elements*(handle~))")
                .header("Host", "api.linkedin.com")
                .header("Connection", "Keep-Alive")
                .header("Authorization", "Bearer " + accessToken)
                .execute();
        System.out.println(emailResponse.body());
        JSONObject emailObj = JSONObject.parseObject(emailResponse.body());
        if (emailObj.containsKey("elements")) {
            email = emailObj.getJSONArray("elements")
                    .getJSONObject(0)
                    .getJSONObject("handle~")
                    .getString("emailAddress");
        }
        return email;
    }

    private String getUserName(JSONObject userInfoObject, String nameKey) {
        String firstName;
        JSONObject firstNameObj = userInfoObject.getJSONObject(nameKey);
        JSONObject localizedObj = firstNameObj.getJSONObject("localized");
        JSONObject preferredLocaleObj = firstNameObj.getJSONObject("preferredLocale");
        firstName = localizedObj.getString(preferredLocaleObj.getString("language") + "_" + preferredLocaleObj.getString("country"));
        return firstName;
    }

    @Override
    public AuthResponse refresh(AuthToken oldToken) {
        if (StringUtils.isEmpty(oldToken.getRefreshToken())) {
            throw new AuthException(AuthResponseStatus.UNSUPPORTED);
        }
        String refreshTokenUrl = this.urlBuilder.getRefreshUrl(oldToken.getRefreshToken());
        return AuthResponse.builder()
                .code(AuthResponseStatus.SUCCESS.getCode())
                .data(this.getToken(refreshTokenUrl))
                .build();
    }

    private void checkResponse(JSONObject userInfoObject) {
        if (userInfoObject.containsKey("error")) {
            throw new AuthException(userInfoObject.getString("error_description"));
        }
    }

    /**
     * 获取token，适用于获取access_token和刷新token
     *
     * @param accessTokenUrl 实际请求token的地址
     * @return token对象
     */
    private AuthToken getToken(String accessTokenUrl) {
        HttpResponse response = HttpRequest.post(accessTokenUrl)
                .header("Host", "www.linkedin.com")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .execute();
        String accessTokenStr = response.body();
        JSONObject accessTokenObject = JSONObject.parseObject(accessTokenStr);

        this.checkResponse(accessTokenObject);

        return AuthToken.builder()
                .accessToken(accessTokenObject.getString("access_token"))
                .expireIn(accessTokenObject.getIntValue("expires_in"))
                .refreshToken(accessTokenObject.getString("refresh_token"))
                .build();
    }
}

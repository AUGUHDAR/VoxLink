package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"time"
)

const (
	httpUserAgent  = "VoxLink-App/" + appVersion
	httpTimeout    = 10 * time.Second
	maxResponseLen = 4 << 20
)

// APIError 服务器/网络错误。Code/Message 透传给前端。
type APIError struct {
	Code    string
	Message string
	Status  int
}

func (e *APIError) Error() string {
	if e.Message != "" {
		return e.Code + ": " + e.Message
	}
	return e.Code
}

// envelope 服务器统一响应信封。
type envelope struct {
	Success bool            `json:"success"`
	Data    json.RawMessage `json:"data"`
	Error   string          `json:"error"`
	Message string          `json:"message"`
}

// apiClient 封装 VoxLink HTTP API。
type apiClient struct {
	http *http.Client
	// upload 专用于 multipart 附件提交：整体不设超时（500MB 上传在慢上行下必然超过
	// 10s 的通用超时），改由请求级 context 控制上限。
	upload *http.Client
}

func newAPIClient() *apiClient {
	return &apiClient{
		http:   &http.Client{Timeout: httpTimeout},
		upload: &http.Client{},
	}
}

// buildRequestURL 构造 {base}/?route=<route>&<query> 形式的请求 URL。
func buildRequestURL(baseURL, route string, query url.Values) (string, error) {
	u, err := url.Parse(baseURL)
	if err != nil {
		return "", &APIError{Code: "NETWORK", Message: "服务器地址无效: " + err.Error()}
	}
	if u.Scheme != "http" && u.Scheme != "https" {
		return "", &APIError{Code: "NETWORK", Message: "服务器地址必须以 http:// 或 https:// 开头"}
	}
	if u.Host == "" {
		return "", &APIError{Code: "NETWORK", Message: "服务器地址缺少主机名"}
	}
	if u.Path == "" {
		u.Path = "/"
	}
	q := u.Query()
	q.Set("route", route)
	for k, vs := range query {
		for _, v := range vs {
			q.Set(k, v)
		}
	}
	u.RawQuery = q.Encode()
	return u.String(), nil
}

// do 执行一次 API 请求并解析响应信封，成功返回 data 字段原始 JSON。
func (c *apiClient) do(baseURL, method, route string, query url.Values, body any) (json.RawMessage, error) {
	reqURL, err := buildRequestURL(baseURL, route, query)
	if err != nil {
		return nil, err
	}

	var rd io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return nil, &APIError{Code: "INTERNAL", Message: "请求体序列化失败: " + err.Error()}
		}
		rd = bytes.NewReader(b)
	}

	req, err := http.NewRequest(method, reqURL, rd)
	if err != nil {
		return nil, &APIError{Code: "NETWORK", Message: "构造请求失败: " + err.Error()}
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("User-Agent", httpUserAgent)
	req.Header.Set("X-VoxLink-Version", appVersion)

	resp, err := c.http.Do(req)
	if err != nil {
		return nil, &APIError{Code: "NETWORK", Message: "网络请求失败: " + err.Error()}
	}
	return c.finish(resp)
}

// finish 读取并解析统一响应信封，成功返回 data 原始 JSON。
func (c *apiClient) finish(resp *http.Response) (json.RawMessage, error) {
	defer resp.Body.Close()

	data, err := io.ReadAll(io.LimitReader(resp.Body, maxResponseLen))
	if err != nil {
		return nil, &APIError{Code: "NETWORK", Message: "读取响应失败: " + err.Error()}
	}

	var env envelope
	if err := json.Unmarshal(data, &env); err != nil {
		return nil, &APIError{
			Code:    fmt.Sprintf("HTTP_%d", resp.StatusCode),
			Message: fmt.Sprintf("响应解析失败（HTTP %d）", resp.StatusCode),
			Status:  resp.StatusCode,
		}
	}

	if resp.StatusCode == http.StatusTooManyRequests {
		code := env.Error
		if code == "" {
			code = "RATE_LIMITED"
		}
		msg := env.Message
		if msg == "" {
			msg = "请求过于频繁，请稍后重试"
		}
		if ra := resp.Header.Get("Retry-After"); ra != "" {
			msg += fmt.Sprintf("（请 %s 秒后重试）", ra)
		}
		return nil, &APIError{Code: code, Message: msg, Status: resp.StatusCode}
	}

	if !env.Success {
		code := env.Error
		if code == "" {
			code = fmt.Sprintf("HTTP_%d", resp.StatusCode)
		}
		msg := env.Message
		if msg == "" {
			msg = "服务器返回错误（HTTP " + strconv.Itoa(resp.StatusCode) + "）"
		}
		return nil, &APIError{Code: code, Message: msg, Status: resp.StatusCode}
	}

	if len(env.Data) == 0 {
		env.Data = json.RawMessage("null")
	}
	return env.Data, nil
}

// post 发送 POST 请求并把 data 解析到 out。
func (c *apiClient) post(baseURL, route string, body, out any) error {
	raw, err := c.do(baseURL, http.MethodPost, route, nil, body)
	if err != nil {
		return err
	}
	if out == nil {
		return nil
	}
	return json.Unmarshal(raw, out)
}

// get 发送 GET 请求并把 data 解析到 out。
func (c *apiClient) get(baseURL, route string, query url.Values, out any) error {
	raw, err := c.do(baseURL, http.MethodGet, route, query, nil)
	if err != nil {
		return err
	}
	if out == nil {
		return nil
	}
	return json.Unmarshal(raw, out)
}

// uploadTimeout 附件上传上限：与模组侧一致（慢上行下 500MB 远超通用 10s 超时）。
const uploadTimeout = 30 * time.Minute

// postMultipart 以 multipart/form-data 提交字段与文件，并把 data 解析到 out。
// 附件经 io.Pipe 流式写出，不整体读进内存。
func (c *apiClient) postMultipart(baseURL, route string, fields map[string]string, files []string, out any) error {
	reqURL, err := buildRequestURL(baseURL, route, nil)
	if err != nil {
		return err
	}

	pr, pw := io.Pipe()
	mw := multipart.NewWriter(pw)
	// boundary 在写协程启动前取好：之后 mw 只归写协程使用
	contentType := mw.FormDataContentType()
	go func() {
		err := func() error {
			for k, v := range fields {
				if err := mw.WriteField(k, v); err != nil {
					return err
				}
			}
			for _, p := range files {
				if err := writeMultipartFile(mw, p); err != nil {
					return err
				}
			}
			return mw.Close()
		}()
		_ = pw.CloseWithError(err)
	}()

	ctx, cancel := context.WithTimeout(context.Background(), uploadTimeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, reqURL, pr)
	if err != nil {
		// 两条管道都要关掉：只关写端会让已启动的写协程永久阻塞在满缓冲的管道上，
		// 只关读端则留下写端无人唤醒。
		_ = pr.Close()
		_ = pw.CloseWithError(err)
		return &APIError{Code: "INTERNAL", Message: "构造上传请求失败: " + err.Error()}
	}
	req.Header.Set("Content-Type", contentType)
	req.Header.Set("User-Agent", httpUserAgent)
	req.Header.Set("X-VoxLink-Version", appVersion)

	resp, err := c.upload.Do(req)
	if err != nil {
		return &APIError{Code: "NETWORK", Message: "上传失败: " + err.Error()}
	}
	raw, err := c.finish(resp)
	if err != nil {
		return err
	}
	if out == nil {
		return nil
	}
	return json.Unmarshal(raw, out)
}

func writeMultipartFile(mw *multipart.Writer, path string) error {
	f, err := os.Open(path)
	if err != nil {
		return err
	}
	defer f.Close()
	part, err := mw.CreateFormFile("attachments", filepath.Base(path))
	if err != nil {
		return err
	}
	_, err = io.Copy(part, f)
	return err
}

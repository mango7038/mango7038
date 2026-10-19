<?php
/**
 * Plugin Name: PostFlow Studio Connector
 * Description: Private PostFlow Studio job queue and scheduling bridge for WordPress. Publishing adapters are not yet connected.
 * Version: 0.1.0
 * Requires PHP: 7.4
 */
if (!defined('ABSPATH')) { exit; }

final class PostFlow_Studio_Connector {
    const TYPE = 'postflow_job';
    const EVENT = 'postflow_process_queue';
    const REST_NS = 'postflow/v1';

    public static function boot() {
        add_action('init', [__CLASS__, 'register_type']);
        add_action('rest_api_init', [__CLASS__, 'routes']);
        add_action(self::EVENT, [__CLASS__, 'process_queue']);
        add_filter('cron_schedules', [__CLASS__, 'intervals']);
        add_action('init', [__CLASS__, 'ensure_scheduled']);
    }

    public static function activate() {
        self::register_type();
        self::ensure_scheduled();
        flush_rewrite_rules(false);
    }

    public static function deactivate() {
        wp_clear_scheduled_hook(self::EVENT);
        flush_rewrite_rules(false);
    }

    public static function register_type() {
        register_post_type(self::TYPE, [
            'public' => false, 'show_ui' => false, 'show_in_rest' => false,
            'supports' => ['title'], 'capability_type' => 'post',
        ]);
    }

    public static function intervals($schedules) {
        $schedules['postflow_five_minutes'] = ['interval' => 300, 'display' => 'Every 5 minutes (PostFlow)'];
        return $schedules;
    }

    public static function ensure_scheduled() {
        if (!wp_next_scheduled(self::EVENT)) {
            wp_schedule_event(time() + 60, 'postflow_five_minutes', self::EVENT);
        }
    }

    public static function allowed() {
        // WordPress Application Passwords over HTTPS are supported by WP REST authentication.
        return is_user_logged_in() && current_user_can('manage_options');
    }

    public static function routes() {
        register_rest_route(self::REST_NS, '/health', [
            'methods' => 'GET', 'callback' => [__CLASS__, 'health'],
            'permission_callback' => [__CLASS__, 'allowed'],
        ]);
        register_rest_route(self::REST_NS, '/jobs', [
            ['methods' => 'GET', 'callback' => [__CLASS__, 'list_jobs'], 'permission_callback' => [__CLASS__, 'allowed']],
            ['methods' => 'POST', 'callback' => [__CLASS__, 'save_job'], 'permission_callback' => [__CLASS__, 'allowed']],
        ]);
        register_rest_route(self::REST_NS, '/jobs/(?P<id>\d+)', [
            'methods' => 'GET', 'callback' => [__CLASS__, 'get_job'], 'permission_callback' => [__CLASS__, 'allowed'],
        ]);
    }

    public static function health() {
        return rest_ensure_response(['ok' => true, 'version' => '0.1.0', 'publishing_enabled' => false,
            'timezone' => 'UTC', 'next_cron' => wp_next_scheduled(self::EVENT) ?: null]);
    }

    private static function details($id) {
        $post = get_post($id);
        if (!$post || $post->post_type !== self::TYPE) return null;
        return [
            'id' => (int)$id,
            'client_key' => (string)get_post_meta($id, '_pf_client_key', true),
            'scheduled_at_utc' => (string)get_post_meta($id, '_pf_scheduled_at', true),
            'status' => (string)get_post_meta($id, '_pf_status', true),
            'destinations' => (array)get_post_meta($id, '_pf_destinations', true),
            'caption' => (string)get_post_meta($id, '_pf_caption', true),
            'updated_at_utc' => (string)get_post_meta($id, '_pf_updated_at', true),
        ];
    }

    public static function list_jobs($request) {
        $posts = get_posts(['post_type' => self::TYPE, 'post_status' => 'private',
            'posts_per_page' => 100, 'orderby' => 'date', 'order' => 'DESC']);
        return rest_ensure_response(array_map(function($p) { return self::details($p->ID); }, $posts));
    }

    public static function get_job($request) {
        $result = self::details(absint($request['id']));
        if (!$result) return new WP_Error('postflow_not_found', 'Job not found', ['status' => 404]);
        return rest_ensure_response($result);
    }

    public static function save_job($request) {
        $body = $request->get_json_params();
        if (!is_array($body)) return new WP_Error('postflow_invalid', 'JSON object required', ['status' => 400]);
        $key = sanitize_text_field($body['client_key'] ?? '');
        $stamp = sanitize_text_field($body['scheduled_at_utc'] ?? '');
        $targets = $body['destinations'] ?? [];
        if (!$key || strlen($key) > 128 || !is_array($targets) || count($targets) > 20 ||
            !preg_match('/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/', $stamp) ||
            strtotime($stamp) === false || gmdate('Y-m-d\TH:i:s\Z', strtotime($stamp)) !== $stamp) {
            return new WP_Error('postflow_invalid', 'Valid client_key, UTC timestamp and destinations required', ['status' => 400]);
        }
        $caption = (string)($body['caption'] ?? '');
        if (strlen($caption) > 20000) return new WP_Error('postflow_invalid', 'Caption too long', ['status' => 400]);
        $existing = get_posts(['post_type' => self::TYPE, 'post_status' => 'private',
            'meta_key' => '_pf_client_key', 'meta_value' => $key, 'posts_per_page' => 1]);
        $id = $existing ? $existing[0]->ID : wp_insert_post([
            'post_type' => self::TYPE, 'post_status' => 'private', 'post_title' => 'PostFlow ' . $key,
        ], true);
        if (is_wp_error($id)) return $id;
        $status = (string)get_post_meta($id, '_pf_status', true);
        if (in_array($status, ['published', 'processing'], true)) {
            return new WP_Error('postflow_conflict', 'Cannot overwrite a published or processing job', ['status' => 409]);
        }
        update_post_meta($id, '_pf_client_key', $key);
        update_post_meta($id, '_pf_scheduled_at', $stamp);
        update_post_meta($id, '_pf_destinations', array_map('sanitize_text_field', array_values($targets)));
        update_post_meta($id, '_pf_caption', sanitize_textarea_field($caption));
        update_post_meta($id, '_pf_status', 'scheduled');
        update_post_meta($id, '_pf_updated_at', gmdate('Y-m-d\TH:i:s\Z'));
        return rest_ensure_response(self::details($id));
    }

    public static function process_queue() {
        $jobs = get_posts(['post_type' => self::TYPE, 'post_status' => 'private',
            'meta_key' => '_pf_status', 'meta_value' => 'scheduled', 'posts_per_page' => 100]);
        $now = time();
        foreach ($jobs as $job) {
            $date = get_post_meta($job->ID, '_pf_scheduled_at', true);
            if ($date && strtotime($date) <= $now) {
                // No social provider configured: NEVER pretend a post was published.
                update_post_meta($job->ID, '_pf_status', 'needs_action');
                update_post_meta($job->ID, '_pf_updated_at', gmdate('Y-m-d\TH:i:s\Z'));
            }
        }
    }
}
PostFlow_Studio_Connector::boot();
register_activation_hook(__FILE__, ['PostFlow_Studio_Connector', 'activate']);
register_deactivation_hook(__FILE__, ['PostFlow_Studio_Connector', 'deactivate']);

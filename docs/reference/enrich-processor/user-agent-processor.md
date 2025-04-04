---
navigation_title: "User agent"
mapped_pages:
  - https://www.elastic.co/guide/en/elasticsearch/reference/current/user-agent-processor.html
---

# User agent processor [user-agent-processor]


The `user_agent` processor extracts details from the user agent string a browser sends with its web requests. This processor adds this information by default under the `user_agent` field.

The ingest-user-agent module ships by default with the regexes.yaml made available by uap-java with an Apache 2.0 license. For more details see [https://github.com/ua-parser/uap-core](https://github.com/ua-parser/uap-core).

## Using the user_agent Processor in a Pipeline [using-ingest-user-agent]

$$$ingest-user-agent-options$$$

| Name | Required | Default | Description |
| --- | --- | --- | --- |
| `field` | yes | - | The field containing the user agent string. |
| `target_field` | no | user_agent | The field that will be filled with the user agent details. |
| `regex_file` | no | - | The name of the file in the `config/ingest-user-agent` directory containing the regular expressions for parsing the user agent string. Both the directory and the file have to be created before starting Elasticsearch. If not specified, ingest-user-agent will use the regexes.yaml from uap-core it ships with (see below). |
| `properties` | no | [`name`, `os`, `device`, `original`, `version`] | Controls what properties are added to `target_field`. |
| `extract_device_type` | no | `false` | [beta] Extracts device type from the user agent string on a best-effort basis. |
| `ignore_missing` | no | `false` | If `true` and `field` does not exist, the processor quietly exits without modifying the document |

Here is an example that adds the user agent details to the `user_agent` field based on the `agent` field:

```console
PUT _ingest/pipeline/user_agent
{
  "description" : "Add user agent information",
  "processors" : [
    {
      "user_agent" : {
        "field" : "agent"
      }
    }
  ]
}
PUT my-index-000001/_doc/my_id?pipeline=user_agent
{
  "agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_10_5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/51.0.2704.103 Safari/537.36"
}
GET my-index-000001/_doc/my_id
```

Which returns

```console-result
{
  "found": true,
  "_index": "my-index-000001",
  "_id": "my_id",
  "_version": 1,
  "_seq_no": 22,
  "_primary_term": 1,
  "_source": {
    "agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_10_5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/51.0.2704.103 Safari/537.36",
    "user_agent": {
      "name": "Chrome",
      "original": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_10_5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/51.0.2704.103 Safari/537.36",
      "version": "51.0.2704.103",
      "os": {
        "name": "Mac OS X",
        "version": "10.10.5",
        "full": "Mac OS X 10.10.5"
      },
      "device" : {
        "name" : "Mac"
      }
    }
  }
}
```

### Using a custom regex file [_using_a_custom_regex_file]

To use a custom regex file for parsing the user agents, that file has to be put into the `config/ingest-user-agent` directory and has to have a `.yml` filename extension. The file has to be present at node startup, any changes to it or any new files added while the node is running will not have any effect.

In practice, it will make most sense for any custom regex file to be a variant of the default file, either a more recent version or a customised version.

The default file included in `ingest-user-agent` is the `regexes.yaml` from uap-core: [https://github.com/ua-parser/uap-core/blob/master/regexes.yaml](https://github.com/ua-parser/uap-core/blob/master/regexes.yaml)


### Node Settings [ingest-user-agent-settings]

The `user_agent` processor supports the following setting:

`ingest.user_agent.cache_size`
:   The maximum number of results that should be cached. Defaults to `1000`.

Note that these settings are node settings and apply to all `user_agent` processors, i.e. there is one cache for all defined `user_agent` processors.




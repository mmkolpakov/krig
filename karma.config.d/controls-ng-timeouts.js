module.exports = function (config) {
  config.processKillTimeout = 10000
  config.browserDisconnectTimeout = 10000
  config.browserDisconnectTolerance = 2
  config.browserNoActivityTimeout = 120000
  config.captureTimeout = 120000
}

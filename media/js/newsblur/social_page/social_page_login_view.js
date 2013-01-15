NEWSBLUR.Views.SocialPageLoginSignupView = Backbone.View.extend({
    
    events: {
        "click .NB-logout-button"   : "logout",
        "click .NB-login-button"    : "login",
        "click .NB-request-button"  : "request_invite"
    },

    initialize: function() {
    },
        
    // ==========
    // = Events =
    // ==========
    
    clean: function() {
        this.$('.NB-error').remove();
    },
    
    login: function() {
        this.clean();
        
        var username = this.$('input[name=login_username]').val();
        var password = this.$('input[name=login_password]').val();
        
        NEWSBLUR.assets.login(username, password, _.bind(this.post_login, this), _.bind(this.login_error, this));
    },
    
    post_login: function(data) {
        NEWSBLUR.log(["login data", data]);
        window.location.reload();
    },
    
    login_error: function(data) {
        this.clean();
        
        var error = _.first(_.values(data.errors))[0];
        this.$('.NB-login-popover .NB-popover-inner').append($.make('div', { className: 'NB-error' }, error));
    },
     
    logout: function() {
        NEWSBLUR.assets.logout(_.bind(this.post_logout, this), _.bind(this.logout_error, this));
    },
    
    post_logout: function(data) {
        window.location.reload();
    },
    
    logout_error: function(data) {
        alert('There was an error trying to logout, ouch.');
    },
    
    request_invite: function() {
        this.clean();        
        var email    = this.$('input[name=request_email]').val();
        
        NEWSBLUR.assets.request_invite(email, _.bind(this.post_request_invite, this), _.bind(this.post_request_invite, this));
    },
    
    post_request_invite: function(data) {
        NEWSBLUR.log(["request data", data]);
        this.hide_popovers();
        this.$('.NB-request-toggle-button').html('Invite Requested');
    },
    
    request_invite_error: function(data) {
        this.clean();
        alert('invite error');
        console.log("calling invite_error");
    }        
});